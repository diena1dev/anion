package dev.diena.anion.features.machine

import dev.diena.anion.data.registry.AnionRegistryKey
import dev.diena.anion.data.registry.registries.AnionRegistries
import dev.diena.anion.features.machine.machine_types.debug.BlinkerMachine
import dev.diena.anion.features.machine.machine_types.debug.industry.DebugCargoContainer
import dev.diena.anion.features.machine.machine_types.debug.scripting.DebugDisplayMachine
import dev.diena.anion.features.machine.machine_types.debug.transport.DebugGyroWheel
import dev.diena.anion.features.machine.machine_types.debug.industry.DEBUG_CARGO_CONTAINER_STRUCTURE
import dev.diena.anion.features.machine.machine_types.debug.industry.DebugFurnaceMachine
import dev.diena.anion.features.machine.machine_types.scripting.ControlSeatMachine
import dev.diena.anion.features.machine.machine_types.scripting.mainframe.MainframeMachine
import dev.diena.anion.features.machine.machine_types.debug.transport.DebugThrusterDown
import dev.diena.anion.features.machine.machine_types.debug.transport.DebugThrusterHorizontal
import dev.diena.anion.features.machine.machine_types.debug.transport.DebugThrusterUp
import features.machine.machine_types.basic_test_machine.BasicTestMachine

object AnionMachines {

	val BLINKER = registerMachine { BlinkerMachine() }
	val BASIC_TEST = registerMachine { BasicTestMachine() }
	val DEBUG_FURNACE = registerMachine { DebugFurnaceMachine() }
	val DEBUG_THRUSTER_HORIZONTAL = registerMachine { DebugThrusterHorizontal() }
	val DEBUG_THRUSTER_UP = registerMachine { DebugThrusterUp() }
	val DEBUG_THRUSTER_DOWN = registerMachine { DebugThrusterDown() }
	val DEBUG_GYRO_WHEEL = registerMachine { DebugGyroWheel() }
	val DEBUG_DISPLAY = registerMachine { DebugDisplayMachine() }

	val MAINFRAME = registerMachine { MainframeMachine() }
	val CONTROL_SEAT = registerMachine { ControlSeatMachine() }

	val DEBUG_CARGO_CONTAINER = registerMachine {
		DebugCargoContainer(
			displayName = "DEBUG Cargo Container",
			blockSet = DEBUG_CARGO_CONTAINER_STRUCTURE,
			typeLimit = 67,
			totalCapacity = 69_420,
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
