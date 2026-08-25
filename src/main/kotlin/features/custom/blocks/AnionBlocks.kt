package dev.diena.anion.features.custom.blocks

import dev.diena.anion.data.registry.AnionRegistryKey
import dev.diena.anion.data.registry.registries.AnionRegistries
import dev.diena.anion.extensions.gradient
import dev.diena.anion.features.custom.items.AnionItems
import dev.diena.anion.features.machine.component.MachinePort
import dev.diena.anion.features.transport.AnionDataJunctionBlock
import dev.diena.anion.features.transport.AnionItemChuteBlock
import dev.diena.anion.features.transport.AnionItemPipeBlock
import dev.diena.anion.features.transport.AnionItemPipeJunctionBlock
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Axis
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

	// TODO: AnionPillarBlock
	val COPPER_MACHINE_DISPLAY = registerBlock(
		AnionBlock(
			"Copper Machine Display",
			Instrument.ZOMBIE,
			5,
			styledDisplayName = Component.text("Copper Machine ")
				.gradient(COPPER_TEXT_START, COPPER_TEXT_END)
				.append(Component.text("Display").color(TextColor.color(Color.LIME.asARGB()))),
			interactHandler = { event -> MachinePort.insertHeld(event) }
		)
	)

	val COPPER_MACHINE_VALVE = registerBlock(
		AnionBlock(
			"Copper Machine Valve",
			Instrument.ZOMBIE,
			6,
			styledDisplayName = Component.text("Copper Machine Valve")
				.gradient(COPPER_TEXT_START, COPPER_TEXT_END),
			interactHandler = { event -> MachinePort.insertHeld(event) }
		)
	)

	val COPPER_MACHINE_BUS = registerBlock(
		AnionBlock(
			"Copper Machine Bus",
			Instrument.ZOMBIE,
			7,
			styledDisplayName = Component.text("Copper Machine Bus")
				.gradient(COPPER_TEXT_START, COPPER_TEXT_END),
			interactHandler = { event -> MachinePort.insertHeld(event) }
		)
	)

	val COPPER_MACHINE_DATAPORT = registerBlock(
		AnionBlock(
			"Copper Machine DataPort",
			Instrument.ZOMBIE,
			8,
			styledDisplayName = Component.text("Copper Machine DataPort")
				.gradient(COPPER_TEXT_START, COPPER_TEXT_END),
			interactHandler = { event -> MachinePort.insertHeld(event) }
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
				.gradient(COPPER_TEXT_START, COPPER_TEXT_END),
			interactHandler = { event -> MachinePort.insertHeld(event) }
		)
	)

	// transport components. what each one does lives on its own class.
	val ITEM_PIPE = registerPillarBlock(
		AnionItemPipeBlock(
			"Item Pipe",
			Instrument.ZOMBIE,
			mapOf(
				Axis.X to 10,
				Axis.Y to 11,
				Axis.Z to 12,
			),
		)
	)

	val ITEM_PIPE_JUNCTION = registerBlock(
		AnionItemPipeJunctionBlock(
			"Item Pipe Junction",
			Instrument.ZOMBIE,
			16,
		)
	)

	val ITEM_CHUTE = registerBlock(
		AnionItemChuteBlock(
			"Item Chute",
			Instrument.ZOMBIE,
			17,
		)
	)

	// the data line is the vanilla chain, see: [AnionTransportComponents.byMaterial].
	// TODO: create an AnionBlock component that owns a vanilla block and prevents mutation of it using the same listeners that AnionBlock relies upon.
	//       the mindset behind such a feature would be to allow copper chains and their variants to be used as custom blocks directly....
	//       which would leave al the waxed (default in Anion) variants open for building!
	val DATA_JUNCTION = registerBlock(
		AnionDataJunctionBlock(
			"Data Junction",
			Instrument.ZOMBIE,
			18,
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

	/** every axis's note resolves back to the same block, so structure checks ignore orientation */
	private fun registerPillarBlock(block: AnionPillarBlock): AnionPillarBlock {
		for (note in block.notesByAxis.values) byState[block.instrument to note] = block

		AnionRegistries.BLOCK_REGISTRY.register(
			AnionRegistryKey(block.namespacedKey.key),
			block
		)

		return block
	}

}
