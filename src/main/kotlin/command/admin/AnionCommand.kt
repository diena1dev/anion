package dev.diena.anion.command.admin

import dev.astralchroma.processor.annotations.Command
import dev.astralchroma.processor.annotations.Name
import dev.astralchroma.processor.annotations.Permission
import dev.astralchroma.processor.annotations.Sender
import dev.astralchroma.processor.annotations.Subcommand
import dev.diena.anion.Anion
import dev.diena.anion.Keys
import dev.diena.anion.data.datagen.resourcepack.AnionResourcePackDatagen
import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender

@Command
@Name("anion")
@Permission("${Keys.COMMAND_PERMISSION_TREE}.anion")
/** Admin utilities for the Anion */
object AnionCommand {

	@Subcommand
	/** Generates the data-driven components for Anion features and exports them to a template resource pack. */
	fun packDatagen(

		@Sender sender: CommandSender

	) {

		sender.sendMessage(Component.text("[Datagen] Starting....."))

		val time = System.currentTimeMillis()
		AnionResourcePackDatagen(Anion().dataFolder).generate()
		val timeTaken = time-System.currentTimeMillis()

		sender.sendMessage(Component.text("[Datagen] Finished in ${timeTaken}ms \n" +
				"Exported to `${Anion().dataFolder}/generated/resourcepack`"))
	}

}
