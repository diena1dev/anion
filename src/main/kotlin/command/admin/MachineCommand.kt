package dev.diena.anion.command.admin

import dev.astralchroma.processor.annotations.Command
import dev.astralchroma.processor.annotations.Name
import dev.astralchroma.processor.annotations.Sender
import dev.astralchroma.processor.annotations.Subcommand
import dev.diena.anion.extensions.vec3i
import dev.diena.anion.features.machine.Machine
import dev.diena.anion.features.machine.examples.BlinkerMachine
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
object MachineCommand {

	/** debug: assembles a [BlinkerMachine] with its core at the block the sender is looking at */
	@Subcommand
	fun assembleBlinkerMachine(

		@Sender sender: Player

	) {

		val target = sender.getTargetBlockExact(16) ?: run {
			sender.info("No block in range (max 16 blocks).")
			return
		}

		val level = (sender.world as CraftWorld).handle
		val origin = target.vec3i

		val machine = BlinkerMachine().assemble(level, origin) ?: run {
			sender.info("No blinker structure at $origin.")
			return
		}

		sender.info("Assembled ${machine::class.simpleName} at $origin. Intact: ${machine.intact}")

	}

	/** Assembles whatever machine the clicked block belongs to, at any offset and rotation. */
	@Subcommand
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

	private fun Player.info(message: String) {

		this.sendMessage(
			Component.text("[Machine] ").color(TextColor.color(Color.AQUA.asARGB()))
				.append(Component.text(message).color(TextColor.color(Color.WHITE.asARGB())))
		)

	}

}
