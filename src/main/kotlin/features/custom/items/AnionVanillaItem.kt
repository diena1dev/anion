package dev.diena.anion.features.custom.items

import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.ItemType

/** Wraps a Vanilla Item as the ItemStack in AnionItem. */
class AnionVanillaItem(
	vanillaItemStack: ItemStack,
) : AnionItem(
	displayName = vanillaItemStack.displayName().toString(),
	itemRepresentation = vanillaItemStack.type.asItemType() ?: ItemType.STICK
) {

	override val internalItemStack: ItemStack = vanillaItemStack

}
