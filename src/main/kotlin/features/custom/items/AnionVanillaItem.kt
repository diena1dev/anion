package dev.diena.anion.features.custom.items

import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.ItemType
import java.util.UUID

/** Wraps a Vanilla Item as the ItemStack in AnionItem. */
class AnionVanillaItem(
	vanillaItemStack: ItemStack,
) : AnionItem(
	displayName = "vanilla_item_${UUID.randomUUID().toString().take(9)}",
	itemRepresentation = vanillaItemStack.type.asItemType() ?: ItemType.STICK,
	styledDisplayName = vanillaItemStack.effectiveName()
) {

	override val internalItemStack: ItemStack = vanillaItemStack

}
