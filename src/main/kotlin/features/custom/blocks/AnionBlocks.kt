package dev.diena.anion.features.custom.blocks

import dev.diena.anion.data.registry.AnionRegistryKey
import dev.diena.anion.data.registry.registries.AnionRegistries
import dev.diena.anion.extensions.gradient
import dev.diena.anion.features.custom.items.AnionItems
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.util.RGBLike
import org.bukkit.Color
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

	private val COPPER_TEXT_START = TextColor.color(232, 144, 121)
	private val COPPER_TEXT_END   = TextColor.color(125, 74, 54)

	// copper machine casings
	val COPPER_MACHINE_CASING = registerBlock(
		AnionBlock(
			"Copper Machine Casing",
			Instrument.ZOMBIE,
			4,
			styledDisplayName = Component.text("Copper Machine Casing")
				.gradient(COPPER_TEXT_START, COPPER_TEXT_END)
		)
	)

	// TODO: AnionDirectionalBlock
	val COPPER_MACHINE_DISPLAY = registerBlock(
		AnionBlock(
			"Copper Machine Display",
			Instrument.ZOMBIE,
			5,
			styledDisplayName = Component.text("Copper Machine ")
				.gradient(COPPER_TEXT_START, COPPER_TEXT_END)
				.append(Component.text("Display").color(TextColor.color(Color.LIME.asARGB())))
		)
	)

	val COPPER_MACHINE_VALVE = registerBlock(
		AnionBlock(
			"Copper Machine Valve",
			Instrument.ZOMBIE,
			6,
			styledDisplayName = Component.text("Copper Machine Valve")
				.gradient(COPPER_TEXT_START, COPPER_TEXT_END)
		)
	)

	val COPPER_MACHINE_BUS = registerBlock(
		AnionBlock(
			"Copper Machine Bus",
			Instrument.ZOMBIE,
			7,
			styledDisplayName = Component.text("Copper Machine Bus")
				.gradient(COPPER_TEXT_START, COPPER_TEXT_END)
		)
	)

	val COPPER_MACHINE_DATAPORT = registerBlock(
		AnionBlock(
			"Copper Machine DataPort",
			Instrument.ZOMBIE,
			8,
			styledDisplayName = Component.text("Copper Machine DataPort")
				.gradient(COPPER_TEXT_START, COPPER_TEXT_END)
		)
	)

	fun fromState(instrument: Instrument, note: Int): AnionBlock? = byState[instrument to note]

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
