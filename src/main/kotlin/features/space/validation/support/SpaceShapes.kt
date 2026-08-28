package dev.diena.anion.features.space.validation.support

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Composable [SpaceShape]s. A ring is a predicate over world coordinates, so an arbitrary ring
 * profile is a matter of combining these rather than teaching the generator about rings.
 */
object SpaceShapes {

	/** nothing, anywhere. */
	fun empty() = SpaceShape { _, _, _ -> false }

	/**
	 * A flat annulus centred on [centerX], [centerZ] at height [planeY].
	 *
	 * [innerRadius] and [outerRadius] are horizontal distances from the centre; [verticalThickness]
	 * is the half-height, so a value of 3 gives a band 7 blocks tall.
	 */
	fun ring(

		centerX: Int,
		centerZ: Int,
		planeY: Int,
		innerRadius: Double,
		outerRadius: Double,
		verticalThickness: Int,

	) = SpaceShape { blockX, blockY, blockZ ->

		if (abs(blockY - planeY) > verticalThickness) return@SpaceShape false

		val deltaX = (blockX - centerX).toDouble()
		val deltaZ = (blockZ - centerZ).toDouble()
		val distance = sqrt(deltaX * deltaX + deltaZ * deltaZ)

		distance >= innerRadius && distance <= outerRadius

	}

	/** A solid sphere, for planet cores and lone asteroids. */
	fun sphere(

		centerX: Int,
		centerY: Int,
		centerZ: Int,
		radius: Double,

	) = SpaceShape { blockX, blockY, blockZ ->

		val deltaX = (blockX - centerX).toDouble()
		val deltaY = (blockY - centerY).toDouble()
		val deltaZ = (blockZ - centerZ).toDouble()

		deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ <= radius * radius

	}

	/**
	 * Thins [shape] out to roughly [density] of its cells, so a ring reads as debris rather than a
	 * solid disc. Deterministic in the position and [seed]: the same cell always decides the same
	 * way, which is what lets chunks generate out of order and off-thread.
	 */
	fun scattered(

		shape: SpaceShape,
		density: Double,
		seed: Long,

	) = SpaceShape { blockX, blockY, blockZ ->

		shape.contains(blockX, blockY, blockZ) && positionNoise(blockX, blockY, blockZ, seed) < density

	}

	fun union(

		vararg shapes: SpaceShape

	) = SpaceShape { blockX, blockY, blockZ ->

		shapes.any { shape -> shape.contains(blockX, blockY, blockZ) }

	}

	fun intersect(

		vararg shapes: SpaceShape

	) = SpaceShape { blockX, blockY, blockZ ->

		shapes.all { shape -> shape.contains(blockX, blockY, blockZ) }

	}

	fun subtract(

		base: SpaceShape,
		removed: SpaceShape,

	) = SpaceShape { blockX, blockY, blockZ ->

		base.contains(blockX, blockY, blockZ) && !removed.contains(blockX, blockY, blockZ)

	}

	fun translate(

		shape: SpaceShape,
		offsetX: Int,
		offsetY: Int,
		offsetZ: Int,

	) = SpaceShape { blockX, blockY, blockZ ->

		shape.contains(blockX - offsetX, blockY - offsetY, blockZ - offsetZ)

	}

	/** a stable value in [0, 1) for a world position. splitmix64 finalizer over a mixed key. */
	fun positionNoise(

		blockX: Int,
		blockY: Int,
		blockZ: Int,
		seed: Long,

	): Double {

		var hash = seed
		hash = hash * 6364136223846793005L + (blockX.toLong() * 0x9E3779B97F4A7C15uL.toLong())
		hash = hash * 6364136223846793005L + (blockY.toLong() * 0xC2B2AE3D27D4EB4FuL.toLong())
		hash = hash * 6364136223846793005L + (blockZ.toLong() * 0x165667B19E3779F9uL.toLong())

		hash = hash xor (hash ushr 30)
		hash *= 0xBF58476D1CE4E5B9uL.toLong()
		hash = hash xor (hash ushr 27)
		hash *= 0x94D049BB133111EBuL.toLong()
		hash = hash xor (hash ushr 31)

		return (hash ushr 11).toDouble() / (1L shl 53).toDouble()

	}

}
