package dev.diena.anion.command.admin

import dev.astralchroma.processor.annotations.Command
import dev.astralchroma.processor.annotations.Name
import dev.astralchroma.processor.annotations.Permission
import dev.astralchroma.processor.annotations.Sender
import dev.astralchroma.processor.annotations.Subcommand
import dev.diena.anion.Keys
import dev.diena.anion.extensions.vec3i
import dev.diena.anion.features.machine.Machine
import dev.diena.anion.features.machine.MachineIndex
import dev.diena.anion.features.machine.component.BulkItemBuffer
import dev.diena.anion.features.machine.examples.BlinkerMachine
import dev.diena.anion.features.machine.machine_types.PortedMachine
import dev.diena.anion.features.starship.Starship
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Color
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.entity.Player

// TODO: add permission nodes
// TODO: this whole command is a stand-in for wrench assembly. the wrench does the same two calls:
//       Machine.candidatesAt() on the clicked block, then assemble() on the single candidate.
@Command
@Name("machine")
@Permission("${Keys.COMMAND_PERMISSION_TREE}.machine")
object MachineCommand {

	/** Assembles whatever machine the clicked block belongs to, at any offset and rotation. */
	@Subcommand
	@Permission("${Keys.COMMAND_PERMISSION_TREE}.machine.assemble")
	fun assemble(

		@Sender sender: Player

	) {

		val target = sender.getTargetBlockExact(16) ?: run {
			sender.info("No block in range (max 16 blocks).")
			return
		}

		val level = (sender.world as CraftWorld).handle
		val clicked = target.vec3i

		val candidates = Machine.candidatesAt(level, clicked)

		if (candidates.isEmpty()) {
			sender.info("Unable to assemble a Machine at $clicked.")
			return
		}

		// a block that completes two different structures at once is an ambiguity, not a coin flip
		if (candidates.size > 1) {
			sender.info("Machine ambiguity at $clicked: ${candidates.size} structures match. Break one apart.")
			return
		}

		val candidate = candidates.first()
		val machine = candidate.factory()
			.assemble(level, candidate.origin, candidate.rotation)
			?: run {
				sender.info("Structure matched but could not be claimed — a port block already belongs to another machine.")
				return
			}

		sender.info("Assembled ${machine::class.simpleName} at ${candidate.origin} (${candidate.rotation}). Intact: ${machine.intact}")

	}

	/** Tears down every machine occupying the targeted block. */
	@Subcommand
	@Permission("${Keys.COMMAND_PERMISSION_TREE}.disassemble")
	fun disassemble(

		@Sender sender: Player

	) {

		val machines = machinesAt(sender) ?: return

		// machines may share structure cells, so a single block can belong to more than one
		for (machine in machines) {
			val name = machine.namespacedKey.key
			machine.disassemble()
			sender.info("Disassembled $name.")
		}

	}

	/** Dumps structure, port and buffer state for the machine(s) at the targeted block. */
	@Subcommand
	@Permission("${Keys.COMMAND_PERMISSION_TREE}.debug")
	fun debug(

		@Sender sender: Player

	) {

		val machines = machinesAt(sender) ?: return

		for (machine in machines) {

			val carrier = machine.starship?.uuid?.toString()?.take(8) ?: "none"
			sender.info("${machine.namespacedKey.key} @ ${machine.origin} rot=${machine.rotation} intact=${machine.intact} ship=$carrier")
			sender.info("  cells=${machine.resolvedStructure.size} dirty=${machine.dirty}")

			val ports = (machine as? PortedMachine)?.ports?.values.orEmpty()
			if (ports.isEmpty()) sender.info("  ports: none")
			else {
				val byKind = ports.groupingBy { it.kind }.eachCount().entries.joinToString(" ") { "${it.key}x${it.value}" }
				sender.info("  ports=${ports.size} [$byKind]")

				for (port in ports.sortedBy { it.kind }) {
					sender.info("   ${port.offset} ${port.kind} -> ${port.bufferKey ?: "unbound"}")
				}
			}

			if (machine.buffers.isEmpty()) sender.info("  buffers: none")
			else for (buffer in machine.buffers.values) {

				val types = if (buffer is BulkItemBuffer) " ${buffer.typesUsed()}/${buffer.typeLimit} types" else ""
				sender.info("  buffer ${buffer.key} ${buffer.used()}/${buffer.capacity()}$types ports=${buffer.boundPorts.size}")

				for ((resource, amount) in buffer.contents()) {
					sender.info("   - ${resource.namespacedKey} x$amount")
				}

			}

		}

	}

	/** Every machine holding the block the sender is looking at, or null once the sender has been told why not. */
	private fun machinesAt(sender: Player): List<Machine>? {

		val target = sender.getTargetBlockExact(16) ?: run {
			sender.info("No block in range (max 16 blocks).")
			return null
		}

		val level = (sender.world as CraftWorld).handle
		val cell = target.vec3i

		// grounded machines live in the cell index; carried ones are tracked by their ship instead
		val machines = MachineIndex.machinesAt(level, cell) +
			Starship.starshipAt(level, cell)?.machines?.machinesHolding(cell).orEmpty()

		if (machines.isEmpty()) {
			sender.info("No machine at $cell.")
			return null
		}

		return machines.distinct()

	}

	private fun Player.info(message: String) {

		this.sendMessage(
			Component.text("[Machine] ").color(TextColor.color(Color.AQUA.asARGB()))
				.append(Component.text(message).color(TextColor.color(Color.WHITE.asARGB())))
		)

	}

}
