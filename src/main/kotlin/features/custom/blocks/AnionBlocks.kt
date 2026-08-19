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
import org.bukkit.block.BlockFace

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

	// power port. a machine draws its whole energy demand through these, so the conduit count on the
	// casing is what caps how fast it can be fed.
	val COPPER_MACHINE_CONDUIT = registerBlock(
		AnionBlock(
			"Copper Machine Conduit",
			Instrument.ZOMBIE,
			9,
			styledDisplayName = Component.text("Copper Machine Conduit")
				.gradient(COPPER_TEXT_START, COPPER_TEXT_END)
		)
	)

	// transport conduits. flow follows the block's facing, so a straight run only takes items in
	// through its back face — turning a corner is what the junction is for.
	val COPPER_PIPE = registerDirectionalBlock(
		AnionDirectionalBlock(
			"Copper Pipe",
			Instrument.ZOMBIE,
			mapOf(
				BlockFace.NORTH to 10,
				BlockFace.EAST  to 11,
				BlockFace.SOUTH to 12,
				BlockFace.WEST  to 13,
			),
			styledDisplayName = Component.text("Copper Pipe")
				.gradient(COPPER_TEXT_START, COPPER_TEXT_END)
		)
	)

	val COPPER_PIPE_VERTICAL = registerDirectionalBlock(
		AnionDirectionalBlock(
			"Copper Pipe Vertical",
			Instrument.ZOMBIE,
			mapOf(
				BlockFace.UP   to 14,
				BlockFace.DOWN to 15,
			),
			styledDisplayName = Component.text("Copper Pipe Vertical")
				.gradient(COPPER_TEXT_START, COPPER_TEXT_END)
		)
	)

	// takes items in on any face and passes them out of every other one
	val COPPER_PIPE_JUNCTION = registerBlock(
		AnionBlock(
			"Copper Pipe Junction",
			Instrument.ZOMBIE,
			16,
			styledDisplayName = Component.text("Copper Pipe Junction")
				.gradient(COPPER_TEXT_START, COPPER_TEXT_END)
		)
	)

	// item adapter. sits against a machine's bus port and drives what the port only provides access to.
	// no facing: it takes the port on whichever side has one and passes items out of any other.
	val COPPER_CHUTE = registerBlock(
		AnionBlock(
			"Copper Chute",
			Instrument.ZOMBIE,
			17,
			styledDisplayName = Component.text("Copper Chute")
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

	/** every facing's note resolves back to the same block, so structure checks ignore rotation */
	private fun registerDirectionalBlock(block: AnionDirectionalBlock): AnionDirectionalBlock {
		for (note in block.notesByFacing.values) byState[block.instrument to note] = block

		AnionRegistries.BLOCK_REGISTRY.register(
			AnionRegistryKey(block.namespacedKey.key),
			block
		)

		return block
	}

}
