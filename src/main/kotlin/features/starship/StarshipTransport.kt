package dev.diena.anion.features.starship

import dev.diena.anion.extensions.plus
import dev.diena.anion.extensions.rotateAround
import dev.diena.anion.extensions.vec3i
import dev.diena.anion.features.transport.AnionTransportComponents
import net.minecraft.core.Vec3i
import net.minecraft.world.level.block.Rotation
import org.bukkit.block.Block
import org.bukkit.craftbukkit.block.data.CraftBlockData
import java.util.concurrent.ConcurrentHashMap

/** Every transport component cell carried by a [Starship]. */
class StarshipTransport private constructor() {

	private lateinit var starship: Starship

	/** world cells on this ship that transport can drive or carry through */
	private val carried: MutableSet<Vec3i> = ConcurrentHashMap.newKeySet()

	companion object {

		/** creates a new component set for the given starship */
		fun new(

			starship: Starship

		) : StarshipTransport {

			val instance = StarshipTransport()

			instance.starship = starship

			return instance

		}

	}

	/** every carried component cell, in world space */
	val cells: Set<Vec3i> get() = this.carried

	/** re-derives the whole set from the ship's blocks. call after blockHashMap is (re)built. */
	// classifies the stored BlockStates rather than reading the world, so loading a ship cannot pull in
	// every chunk it spans just to find out where its pipes are.
	fun rebuild() {

		this.carried.clear()

		for ((cell, blockState) in this.starship.blockHashMap) {

			if (AnionTransportComponents.of(CraftBlockData.createData(blockState)) == null) continue

			this.carried += cell

		}

	}

	/** claims [block] if it is a component. */
	fun add(

		block: Block

	) {

		if (AnionTransportComponents.at(block) == null) return

		this.carried += block.vec3i

	}

	/** drops [cell], whatever was there. */
	fun remove(

		cell: Vec3i

	) {

		this.carried.remove(cell)

	}

	/////////////////////////
	///// MOVEMENT OPERATIONS
	/////////////////////////

	/** follows a ship translation. call *after* the ship's blockHashMap has been rewritten. */
	fun translate(

		vectorMovedIn: Vec3i

	) {

		val moved = this.carried.mapTo(HashSet()) { it + vectorMovedIn }

		this.carried.clear()
		this.carried += moved

	}

	/** follows a ship rotation about [Starship.origin], which rotation leaves unchanged. */
	fun rotate(

		rotation: Rotation

	) {

		val rotated = this.carried.mapTo(HashSet()) { it.rotateAround(this.starship.origin, rotation) }

		this.carried.clear()
		this.carried += rotated

	}

}
