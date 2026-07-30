package dev.diena.anion.features.machine.machine_types

import dev.diena.anion.features.machine.BlockSet
import dev.diena.anion.features.machine.Machine
import dev.diena.anion.features.machine.component.MachinePort

class PortedMachine(

	displayName: String,
	blockSet: BlockSet?

) : Machine(displayName, blockSet) {

	lateinit var buffer: Any
	lateinit var ports: MutableSet<MachinePort>

	override fun onAssemble() {
		super.onAssemble()

		// called AFTER onAssemble to have the populated machineStructureBlocks
		for ((vec, block) in this.machineStructureBlocks) {



		}

	}

	override fun tick() {
		TODO("Not yet implemented")
	}

	override fun slowTick() {
		TODO("Not yet implemented")
	}

}
