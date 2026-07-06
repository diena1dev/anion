package dev.diena.anion.command.admin

import dev.astralchroma.processor.annotations.Command
import dev.astralchroma.processor.annotations.Inferred
import dev.astralchroma.processor.annotations.Name
import dev.astralchroma.processor.annotations.Permission
import dev.astralchroma.processor.annotations.Sender
import dev.diena.anion.Anion
import dev.diena.anion.Keys
import dev.diena.anion.data.datagen.resourcepack.AnionResourcePackDatagen
import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender

@Command
@Name("resourcepackdatagen")
@Permission("${Keys.COMMAND_PERMISSION_TREE}.admin.resourcepackdatagen")
object ResourcePackDatagenCommand {

    @Inferred
    fun self(
        @Sender sender: CommandSender
    ) {

        sender.sendMessage(Component.text("[Started Datagen]"))
        AnionResourcePackDatagen(Anion().dataFolder).generate()
        sender.sendMessage(Component.text("[Finished Datagen] \n" +
                "Exported to `${Anion().dataFolder}/generated/resourcepack`"))
    }

}
