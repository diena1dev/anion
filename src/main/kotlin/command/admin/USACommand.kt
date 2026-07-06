package dev.diena.anion.command.admin

import dev.astralchroma.processor.annotations.Command
import dev.astralchroma.processor.annotations.Inferred
import dev.astralchroma.processor.annotations.Name
import dev.astralchroma.processor.annotations.Permission
import dev.astralchroma.processor.annotations.Sender
import dev.diena.anion.Keys
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Color
import org.bukkit.FireworkEffect
import org.bukkit.entity.Firework
import org.bukkit.entity.Player
import org.bukkit.inventory.meta.FireworkMeta

/** Horizon's End uses the `/usa` command as an alias to `/unsetall`, which is lame. >:3 */
@Command
@Name("usa")
@Permission("${Keys.COMMAND_PERMISSION_TREE}.admin.usa")
object USACommand {

    @Inferred
    fun self(

        @Sender sender: Player // must be player

    ) {

        sender.sendMessage(
            // AM ERI CA
            Component
                .text("RAHHHHH ").color(TextColor.color(Color.GRAY.asARGB()))
                .append(Component.text("AM").color(TextColor.color(Color.RED.asARGB())))
                .append(Component.text("ERI").color(TextColor.color(Color.WHITE.asARGB())))
                .append(Component.text("CA").color(TextColor.color(Color.BLUE.asARGB())))
                .append(Component.text("!!!!!")).color(TextColor.color(Color.GRAY.asARGB()))
        )

        for (i in 1..50) {

            // the RED
            sender.world.spawn(
                sender.location,
                Firework::class.java,
                {
                    firework ->
                    firework.fireworkMeta.addEffect(
                        FireworkEffect.builder().with(FireworkEffect.Type.BURST).flicker(true).trail(false).withColor(Color.RED).build()
                    )
                }
            )

            // WHITE
            sender.world.spawn(
                sender.location,
                Firework::class.java,
                {
                        firework ->
                    firework.fireworkMeta.addEffect(
                        FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE).flicker(true).trail(false).withColor(Color.WHITE).build()
                    )
                }
            )

            // AND BLUE
            sender.world.spawn(
                sender.location,
                Firework::class.java,
                {
                        firework ->
                    firework.fireworkMeta.addEffect(
                        FireworkEffect.builder().with(FireworkEffect.Type.STAR).flicker(true).trail(false).withColor(Color.BLUE).build()
                    )
                }
            )

            // OF AMERICA RAHHHHHH!!!!!

        }

    }

}
