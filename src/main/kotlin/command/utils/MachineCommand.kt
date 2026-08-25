package dev.diena.anion.command.utils

import dev.astralchroma.processor.annotations.Command
import dev.astralchroma.processor.annotations.Name
import dev.astralchroma.processor.annotations.Permission
import dev.astralchroma.processor.annotations.Sender
import dev.astralchroma.processor.annotations.Subcommand
import dev.diena.anion.Keys
import dev.diena.anion.extensions.vec3i
import dev.diena.anion.features.machine.Machine
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Color
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.entity.Player

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
			sender.info("Machine ambiguity at $clicked: ${candidates.size} structures match.")
			return
		}

		val candidate = candidates.first()
		val machine = candidate.factory()
			.assemble(level, candidate.origin, candidate.rotation)
			?: run {
				sender.info("A port block in the structure already belongs to another machine.")
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

	/** Empties a buffer onto the floor. The way out of loading the wrong resource into the wrong one. */
	@Subcommand
	@Permission("${Keys.COMMAND_PERMISSION_TREE}.clear")
	fun clearBuffer(

		@Sender sender: Player,
		buffer: String,

	) {

		val machines = machinesAt(sender) ?: return

		for (machine in machines) {

			val store = machine.buffers[buffer]

			if (store == null) {
				val known = machine.buffers.keys.joinToString(", ").ifEmpty { "none" }
				sender.info("${machine.namespacedKey.key} has no buffer '$buffer'. it has: $known")
				continue
			}

			if (!store.spillable) {
				sender.info("'$buffer' disassemble the machine to empty this buffer.")
				continue
			}

			val held = store.used()
			machine.spill(store)

			sender.info("Cleared ${machine.namespacedKey.key} buffer '$buffer', dropped $held.")

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
			for (line in machine.debugReport()) sender.info(line)
		}

	}

	/////////////
	///// HELPERS
	/////////////

	/** Every machine holding the block the sender is looking at, or null once the sender has been told why not. */
	private fun machinesAt(sender: Player): List<Machine>? {

		val target = sender.getTargetBlockExact(16) ?: run {
			sender.info("No block in range (max 16 blocks).")
			return null
		}

		val level = (sender.world as CraftWorld).handle
		val cell = target.vec3i

		val machines = Machine.machinesAt(level, cell)

		if (machines.isEmpty()) {
			sender.info("No machine at $cell.")
			return null
		}

		return machines

	}

	private fun Player.info(message: String) {

		this.sendMessage(
			Component.text("[Machine] ").color(TextColor.color(Color.AQUA.asARGB()))
				.append(Component.text(message).color(TextColor.color(Color.WHITE.asARGB())))
		)

	}

}
