package dev.diena.anion.features.machine.machine_types

import dev.diena.anion.features.machine.Machine

class PortedTankMachine(

	displayName: String

) : Machine(displayName, null) {

	// TODO: buffers and ports
	lateinit var buffer: Any
	lateinit var ports: Set<Any>

	/** used like the init {} block of a class, assign lateinit vars here */
	override fun onAssemble() {

		TODO("Not yet implemented")

		super.onAssemble()

	}

	override fun isIntact(): Boolean {

		TODO("Not yet implemented: flood-fill structure checks.")

		return super.isIntact()

	}

	override fun tick() {

		TODO("Not yet implemented")

	}

	override fun slowTick() {

		TODO("Not yet implemented")

	}

}
