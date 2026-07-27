package dev.diena.anion.command.admin

import dev.astralchroma.processor.annotations.Command
import dev.astralchroma.processor.annotations.Name
import dev.astralchroma.processor.annotations.Sender
import dev.astralchroma.processor.annotations.Subcommand
import dev.diena.anion.extensions.vec3i
import dev.diena.anion.features.machine.examples.BlinkerMachine
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Color
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.entity.Player

// TODO: add permission nodes
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

        val machine = BlinkerMachine().assemble(level, origin)

        sender.info("Assembled ${machine::class.simpleName} at $origin. Intact: ${machine.intact}")

    }

    private fun Player.info(message: String) {

        this.sendMessage(
            Component.text("[Machine] ").color(TextColor.color(Color.AQUA.asARGB()))
                .append(Component.text(message).color(TextColor.color(Color.WHITE.asARGB())))
        )

    }

}
