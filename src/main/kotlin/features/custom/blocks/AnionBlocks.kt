package dev.diena.anion.features.custom.blocks

import dev.diena.anion.data.registry.AnionRegistryKey
import dev.diena.anion.data.registry.registries.AnionRegistries
import dev.diena.anion.features.custom.items.AnionItems
import org.bukkit.Instrument

object AnionBlocks {

	private val byState: MutableMap<Pair<Instrument, Int>, AnionBlock> = mutableMapOf()

	val TEST_BLOCK = registerBlock(
		AnionBlock(
			"Test Block",
			Instrument.ZOMBIE,
			0
		)
	)

	val URANIUM_ORE = registerBlock(
		AnionBlock(
			"Uranium Ore",
			Instrument.ZOMBIE,
			1,
			drops = AnionItems.RAW_URANIUM_ORE.asItemStack()
		)
	)

	val URANIUM_ORE_BLOCK = registerBlock(
		AnionBlock(
			"Uranium Ore Block",
			Instrument.ZOMBIE,
			2
		)
	)

	val URANIUM_BLOCK = registerBlock(
		AnionBlock(
			"Uranium Block",
			Instrument.ZOMBIE,
			3
		)
	)

	fun fromState(instrument: Instrument, note: Int): AnionBlock? = byState[instrument to note]

	@Suppress("SameParameterValue")
	private fun registerBlock(block: AnionBlock): AnionBlock {
		val key = block.instrument to block.note
		byState[key] = block

		AnionRegistries.BLOCK_REGISTRY.register(
			AnionRegistryKey(block.namespacedKey.key),
			block
		)

		return block
	}

}
