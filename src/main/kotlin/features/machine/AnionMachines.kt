package dev.diena.anion.features.machine

import dev.diena.anion.data.registry.AnionRegistryKey
import dev.diena.anion.data.registry.registries.AnionRegistries
import dev.diena.anion.features.machine.examples.BlinkerMachine
import dev.diena.anion.features.machine.machine_types.CargoContainerMachine
import dev.diena.anion.features.machine.machine_types.MEDIUM_CARGO_CONTAINER_STRUCTURE
import dev.diena.anion.features.machine.machine_types.debug_furnace.DebugFurnaceMachine
import dev.diena.anion.features.machine.machine_types.thrusters.DebugThrusterDown
import dev.diena.anion.features.machine.machine_types.thrusters.DebugThrusterHorizontal
import dev.diena.anion.features.machine.machine_types.thrusters.DebugThrusterUp
import features.machine.machine_types.basic_test_machine.BasicTestMachine

object AnionMachines {

	val BLINKER = registerMachine { BlinkerMachine() }
	val BASIC_TEST = registerMachine { BasicTestMachine() }
	val DEBUG_FURNACE = registerMachine { DebugFurnaceMachine() }
	val DEBUG_THRUSTER_HORIZONTAL = registerMachine { DebugThrusterHorizontal() }
	val DEBUG_THRUSTER_UP = registerMachine { DebugThrusterUp() }
	val DEBUG_THRUSTER_DOWN = registerMachine { DebugThrusterDown() }

	val MEDIUM_CARGO_CONTAINER = registerMachine {
		CargoContainerMachine(
			displayName = "Medium Cargo Container",
			blockSet = MEDIUM_CARGO_CONTAINER_STRUCTURE,
			typeLimit = 10,
			totalCapacity = 24_000,
		)
	}

	/** iterates every registered machine type's factory. */
	val all get() = AnionRegistries.MACHINE_TYPE_REGISTRY.all.values

	/** key is read off a throwaway instance so it never drifts from the type's own namespacedKey. */
	private fun registerMachine(factory: () -> Machine): () -> Machine {

		val key = factory().namespacedKey
		AnionRegistries.MACHINE_TYPE_REGISTRY.register(AnionRegistryKey(key.key), factory)

		return factory

	}

}
