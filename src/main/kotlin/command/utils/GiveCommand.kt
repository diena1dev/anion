package dev.diena.anion.command.utils

import dev.astralchroma.processor.annotations.Command
import dev.astralchroma.processor.annotations.Inferred
import dev.astralchroma.processor.annotations.Name
import dev.astralchroma.processor.annotations.Permission
import dev.astralchroma.processor.annotations.Sender
import dev.diena.anion.Keys
import dev.diena.anion.data.registry.registries.AnionRegistries
import net.kyori.adventure.text.Component
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player

@Command
@Name("give")
@Permission("${Keys.COMMAND_PERMISSION_TREE}.give")
object GiveCommand {

    /** FIXME: AnionItem/AnionBlock suggestion provider */
    @Inferred
    fun self(
        @Sender sender: Player, // require player
        itemKey: String
    ) {

        fun fail() = sender.sendMessage(Component.text("ohnaurrrr we can't fwind the item sworry >w<"))

        val attemptItem = AnionRegistries.ITEM_REGISTRY.getValue(NamespacedKey.fromString(itemKey) ?: return fail())
        if (attemptItem == null) return fail()

        val itemToGive = attemptItem.asItemStack()

        sender.sendMessage(
            Component
            .text("Gave 1 ")
            .append(itemToGive.displayName())
            .append(Component.text(" to "))
            .append(sender.displayName()))
        sender.give(itemToGive)

    }
}