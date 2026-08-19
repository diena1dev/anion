package dev.diena.anion.features.machine

import dev.diena.anion.data.registry.AnionRegistryKey
import dev.diena.anion.data.registry.registries.AnionRegistries
import dev.diena.anion.features.machine.examples.BlinkerMachine
import features.machine.machine_types.basic_test_machine.BasicTestMachine

object AnionMachines {

	val BLINKER = registerMachine { BlinkerMachine() }
	val BASIC_TEST = registerMachine { BasicTestMachine() }

	/** iterates every registered machine type's factory. */
	val all get() = AnionRegistries.MACHINE_TYPE_REGISTRY.all.values

	/** key is read off a throwaway instance so it never drifts from the type's own namespacedKey. */
	private fun registerMachine(factory: () -> Machine): () -> Machine {

		val key = factory().namespacedKey
		AnionRegistries.MACHINE_TYPE_REGISTRY.register(AnionRegistryKey(key.key), factory)

		return factory

	}

}
