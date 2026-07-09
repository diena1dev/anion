package dev.diena.anion.features.custom.items

import dev.diena.anion.data.registry.AnionRegistryKey
import dev.diena.anion.data.registry.registries.AnionRegistries
import dev.diena.anion.features.custom.blocks.AnionBlock
import dev.diena.anion.features.custom.blocks.AnionBlocks
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Color
import org.bukkit.inventory.ItemType

object AnionItems {

	// items
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

	val RAW_URANIUM_ORE = registerItem(
		AnionItem(
			displayName = "Raw Uranium Ore",
			itemRepresentation = ItemType.AMETHYST_SHARD
		)
	)

	val URANIUM_INGOT = registerItem(
		AnionItem(
			displayName = "Uranium Ingot",
			itemRepresentation = ItemType.AMETHYST_SHARD
		)
	)

	// blocks
	val TEST_BLOCK = registerBlock(AnionBlocks.TEST_BLOCK)
	val URANIUM_ORE = registerBlock(AnionBlocks.URANIUM_ORE)
	val URANIUM_ORE_BLOCK = registerBlock(AnionBlocks.URANIUM_ORE_BLOCK)
	val URANIUM_BLOCK = registerBlock(AnionBlocks.URANIUM_BLOCK)

	private fun registerItem(item: AnionItem) = item.also {
		AnionRegistries.ITEM_REGISTRY.register(AnionRegistryKey(it.namespacedKey.key), it)
	}

	// placeholder, see the 26.1 vanilla jar's Item class for the planned structure.
	private fun registerBlock(block: AnionBlock): AnionItem {
		val blockItem = AnionBlockItem(block)

		AnionRegistries.ITEM_REGISTRY.register(
			AnionRegistryKey(block.namespacedKey.key),
			blockItem
		)

		return blockItem
	}

}
