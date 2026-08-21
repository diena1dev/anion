package dev.diena.anion.command.admin

import dev.astralchroma.processor.annotations.Command
import dev.astralchroma.processor.annotations.Name
import dev.astralchroma.processor.annotations.Permission
import dev.astralchroma.processor.annotations.Sender
import dev.astralchroma.processor.annotations.Subcommand
import dev.diena.anion.Keys
import dev.diena.anion.extensions.vec3i
import dev.diena.anion.features.transport.AnionTransport
import dev.diena.anion.features.transport.AnionTransportIndex
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Color
import org.bukkit.entity.Player

// TODO: add permission nodes
@Command
@Name("transport")
@Permission("${Keys.COMMAND_PERMISSION_TREE}.transport")
object TransportCommand {

	/** Dumps what transport sees at the targeted block, and where a run leaving it stops. */
	@Subcommand
	@Permission("${Keys.COMMAND_PERMISSION_TREE}.transport.debug")
	fun debug(

		@Sender sender: Player

	) {

		val target = sender.getTargetBlockExact(16) ?: run {
			sender.info("No block in range (max 16 blocks).")
			return
		}

		for (line in AnionTransport.describe(sender.world, target.vec3i)) sender.info(line)

	}

	/** Re-indexes the targeted block, for one placed by something that fires no block event. */
	@Subcommand
	fun index(

		@Sender sender: Player

	) {

		val target = sender.getTargetBlockExact(16) ?: run {
			sender.info("No block in range (max 16 blocks).")
			return
		}

		if (!AnionTransportIndex.isComponent(target)) {
			sender.info("${target.vec3i} is not a transport component.")
			return
		}

		AnionTransportIndex.register(target)
		sender.info("Indexed ${target.vec3i}.")

	}

	private fun Player.info(message: String) {

		this.sendMessage(
			Component.text("[Transport] ").color(TextColor.color(Color.ORANGE.asARGB()))
				.append(Component.text(message).color(TextColor.color(Color.WHITE.asARGB())))
		)

	}

}
