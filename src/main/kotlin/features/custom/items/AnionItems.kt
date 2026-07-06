package dev.diena.anion.features.custom.items

import dev.diena.anion.data.registry.AnionRegistryKey
import dev.diena.anion.data.registry.registries.AnionRegistries
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Color
import org.bukkit.inventory.ItemType

object AnionItems {

	val TEST_ITEM = registerItem(
		AnionItem(
			displayName = "Test Item",
			itemRepresentation = ItemType.AMETHYST_SHARD,
			styledDisplayName = Component.text("Test Item").color(TextColor.color(Color.PURPLE.asARGB()))
		)
	)

	val ANION_BLASTER_PISTOL = registerItem(
		AnionBlasterPistolItem()
	)

	private fun registerItem(item: AnionItem) = item.also {
		AnionRegistries.ITEM_REGISTRY.register(AnionRegistryKey(it.namespacedKey.key), it)
	}

	// placeholder, see the 26.1 vanilla jar's Item class for the planned structure.
	private fun registerBlock() {

	}

}
