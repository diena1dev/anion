package dev.diena.anion.features.starship

import dev.diena.anion.extensions.plus
import dev.diena.anion.extensions.rotateAround
import dev.diena.anion.features.machine.Machine
import dev.diena.anion.features.machine.MachineIndex
import net.minecraft.core.Vec3i
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Rotation
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Every [Machine] carried by a [Starship], and the transforms that keep them attached.
 */
class StarshipMachines private constructor() {

	private lateinit var starship: Starship

	/** machines whose core block sits on this ship, keyed by machine uuid */
	private val machines: MutableMap<UUID, Machine> = ConcurrentHashMap()

	companion object {

		/** creates a new machine set for the given starship */
		fun new(

			starship: Starship

		) : StarshipMachines {

			val instance = StarshipMachines()

			instance.starship = starship

			return instance

		}

	}

	/////////////////////////
	///// OWNERSHIP OPERATIONS
	/////////////////////////

	/** claims [machine]. a machine belongs to at most one ship, so it leaves its previous one first. */
	fun attach(

		machine: Machine

	) {

		machine.starship?.machines?.detach(machine)

		this.machines[machine.uuid] = machine
		machine.starship = this.starship

		// a carried machine's cells move every tick, so it leaves the world-cell index entirely
		MachineIndex.unregister(machine)

	}

	/** releases [machine]. if machine in function doesn't exist on the starship, nothing happens. */
	fun detach(

		machine: Machine

	) {

		this.machines.remove(machine.uuid)
		if (machine.starship === this.starship) machine.starship = null

		if (machine.starship == null) MachineIndex.register(machine) // grounded again, so it goes back in

	}

	/** releases every machine and returns them. used when the ship is destroyed, emptied or unloaded. */
	// machines outlive their ship, allowing disassembled starships' machines to keep buffers and ports intact.
	fun detachAll(): List<Machine> {

		val released = this.machines.values.toList()
		for (machine in released) detach(machine)

		return released

	}

	/** re-derives the whole set from world state. call after blockHashMap is (re)built. */
	fun rebuild() {

		detachAll()

		for (machine in Machine.activeMachines.values) {

			if (machine.level != this.starship.level) continue
			if (!this.starship.blockHashMap.containsKey(machine.origin)) continue

			attach(machine)

		}

	}

	/** the machine whose core block sits at [vec], if this ship carries one. */
	fun coreAt(

		vec: Vec3i

	) : Machine? = this.machines.values.firstOrNull { it.origin == vec }

	/** every carried machine with a structure cell at [vec]. machines may share cells. */
	// carried machines are absent from MachineIndex, so their cell lookup lives here instead
	fun machinesHolding(

		vec: Vec3i

	) : List<Machine> = this.machines.values.filter { vec in it.worldCells() }

	/** Moves every carried machine into [newLevel]. Used when the ship crosses worlds. */
	fun reassignLevel(

		newLevel: ServerLevel

	) {

		for (machine in this.machines.values) machine.reassignLevel(newLevel)

	}

	/////////////////////////
	///// MOVEMENT OPERATIONS
	/////////////////////////

	/** follows a ship translation. call *after* the ship's blockHashMap has been rewritten. */
	fun translate(

		vectorMovedIn: Vec3i

	) {

		for (machine in this.machines.values) {

			machine.relocate(machine.origin + vectorMovedIn, Rotation.NONE)

		}

	}

	/** follows a ship rotation about [Starship.origin], which rotation leaves unchanged. */
	fun rotate(

		rotation: Rotation

	) {

		for (machine in this.machines.values) {

			machine.relocate(machine.origin.rotateAround(this.starship.origin, rotation), rotation)

		}

	}

	/** re-checks every carried machine's structure. call once a move has fully settled. */
	fun revalidate() {

		for (machine in this.machines.values) machine.revalidate()

	}

}
