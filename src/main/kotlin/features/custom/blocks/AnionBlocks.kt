package dev.diena.anion.features.custom.blocks

import dev.diena.anion.data.registry.AnionRegistryKey
import dev.diena.anion.data.registry.registries.AnionRegistries
import dev.diena.anion.features.custom.items.AnionItems
import org.bukkit.Instrument
import org.bukkit.block.BlockFace

object AnionBlocks {

	private val byNoteblockState: MutableMap<Pair<Instrument, Int>, AnionNoteblockCustomBlock> = mutableMapOf()
	private val byMushroomState: MutableMap<Pair<MushroomType, Set<BlockFace>>, AnionMushroomCustomBlock> = mutableMapOf()

	val TEST_BLOCK = registerBlock(
		AnionNoteblockCustomBlock(
			"Test Block",
			Instrument.ZOMBIE,
			0
		)
	)

	val URANIUM_ORE = registerBlock(
		AnionNoteblockCustomBlock(
			"Uranium Ore",
			Instrument.ZOMBIE,
			1,
			drops = AnionItems.RAW_URANIUM_ORE.asItemStack()
		)
	)

	val URANIUM_ORE_BLOCK = registerBlock(
		AnionNoteblockCustomBlock(
			"Uranium Ore Block",
			Instrument.ZOMBIE,
			2
		)
	)

	val URANIUM_BLOCK = registerBlock(
		AnionNoteblockCustomBlock(
			"Uranium Block",
			Instrument.ZOMBIE,
			3
		)
	)

	val TEST_MUSHROOM_BLOCK = registerBlock(
		AnionMushroomCustomBlock(
			"Test Mushroom Block",
			MushroomType.BROWN,
			1
		)
	)

	fun fromNoteblockState(instrument: Instrument, note: Int): AnionNoteblockCustomBlock? =
		byNoteblockState[instrument to note]

	fun fromMushroomState(mushroomType: MushroomType, faces: Set<BlockFace>): AnionMushroomCustomBlock? =
		byMushroomState[mushroomType to faces]

	private fun registerBlock(block: AnionNoteblockCustomBlock): AnionNoteblockCustomBlock {
		val key = block.instrument to block.note
		byNoteblockState[key] = block

		AnionRegistries.BLOCK_REGISTRY.register(
			AnionRegistryKey(block.namespacedKey.key),
			block
		)

		return block
	}

	private fun registerBlock(block: AnionMushroomCustomBlock): AnionMushroomCustomBlock {
		val key = block.mushroomType to block.faces
		byMushroomState[key] = block

		AnionRegistries.BLOCK_REGISTRY.register(
			AnionRegistryKey(block.namespacedKey.key),
			block
		)

		return block
	}

}
