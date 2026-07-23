package dev.diena.anion.features.starship

import dev.diena.anion.extensions.blockPos
import net.minecraft.core.Vec3i
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.AABB

class StarshipHitbox private constructor() {

	private lateinit var aabb: AABB         // nms aabb, for entity functions
	private lateinit var starship: Starship // stored starship instance

	companion object {

		/** creates a new starship hitbox */
		fun new(

			starship: Starship

		) : StarshipHitbox {

			val instance = StarshipHitbox()

			instance.starship = starship
			instance.rebuildHitbox() // initialize our nonexistent hitbox first

			return instance

		}

	}

	/** returns a list of nms entities */
	fun getEntitiesWithin() : List<Entity> {

		// grab vanilla entities from the AABB functions
		val foundEntities = this.starship.level.getEntities(null as Entity?, this.aabb) {

			entity ->

			val bp = entity.blockPosition()

			// so apparently you can just do this and it works, thanks kotlin
			for (x in -1..1) for (y in -1..1) for (z in -1..1) {

				if (this.starship.blockHashMap.containsKey(Vec3i(bp.x + x, bp.y + y, bp.z + z))) return@getEntities true

			}

			// if we don't find the entity nearby the ship we just leave it behind :3
			// (do NOT jump off of your ship you will be left behind in the cold, empty void)
			false

		}

		return foundEntities

	}

	/** translates the given hitbox without rebuilding it. */
	fun moveHitbox(

		vec3i: Vec3i

	) {

		this.aabb = this.aabb.move(vec3i.blockPos)

	}

	/** rebuilds the hitbox from the stored starship instance */
	fun rebuildHitbox() {

		var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var minZ = Int.MAX_VALUE
		var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE; var maxZ = Int.MIN_VALUE

		for (v in this.starship.blockHashMap.keys) {

			if (v.x < minX) minX = v.x; if (v.x > maxX) maxX = v.x
			if (v.y < minY) minY = v.y; if (v.y > maxY) maxY = v.y
			if (v.z < minZ) minZ = v.z; if (v.z > maxZ) maxZ = v.z

		}

		// set hitbox to maximum bounds of given map
		aabb = AABB(
			(minX - 1).toDouble(), (minY - 1).toDouble(), (minZ - 1).toDouble(),
			(maxX + 2).toDouble(), (maxY + 2).toDouble(), (maxZ + 2).toDouble()
		)

	}

}
