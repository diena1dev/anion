package dev.diena.anion.features.machine

import dev.diena.anion.data.registry.AnionRegistryKey
import dev.diena.anion.data.registry.registries.AnionRegistries
import dev.diena.anion.features.custom.blocks.AnionBlocks
import org.bukkit.block.BlockType

// contains registered MachineStructure checks and adds them to the registry
object MachineStructures {

	// this would form a structure check for basically a uranium-cell-battery-looking thing
	val BASIC_TEST_MACHINE = registerMachineStructure(
		MachineStructure.new("basic_test_machine")
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
		)

	private fun registerMachineStructure(machineStructure: MachineStructure): MachineStructure {

		AnionRegistries.MACHINE_STRUCTURE_REGISTRY.register(
			AnionRegistryKey(machineStructure.name.lowercase().replace(' ', '_')),
			machineStructure
		)

		return machineStructure
	}

}