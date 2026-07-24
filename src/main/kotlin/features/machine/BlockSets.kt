package dev.diena.anion.features.machine

import dev.diena.anion.features.custom.blocks.AnionBlocks
import org.bukkit.block.BlockType

// TODO: REMOVE FILE
// contains registered MachineStructure checks and adds them to the registry
object BlockSets {

	// this would form a structure check for basically a uranium-cell-battery-looking thing
	val BASIC_TEST_MACHINE =
		BlockSet.new("basic_test_machine")
			.core('C', AnionBlocks.COPPER_MACHINE_DISPLAY)
			.assign('I', AnionBlocks.COPPER_MACHINE_CASING)

			.assign('B', AnionBlocks.COPPER_MACHINE_BUS)
			.assign('V', AnionBlocks.COPPER_MACHINE_VALVE)

			.assign('G', BlockType.WAXED_COPPER_GRATE)
			.assign('U', AnionBlocks.URANIUM_BLOCK)
			.slice(
				"III",
				"III",
				"ICI"
			)
			.slice(
				"IGI",
				"VUB",
				"IGI"
			)
			.slice(
				"IGI",
				"IUI",
				"IGI"
			)
			.slice(
				"III",
				"III",
				"III"
			)
			.build()

}