package dev.diena.anion.features.space.validation.checks

import dev.diena.anion.features.space.validation.CheckGroup
import dev.diena.anion.features.space.validation.support.SpaceShapes
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Ring geometry and the noise that thins it. Pure predicates, no world needed — the generator that
 * consumes them is exercised in the level group, where a real chunk exists to write into.
 */
object SpaceGenChecks {

	private const val CENTER_X = 0
	private const val CENTER_Z = 0
	private const val PLANE_Y = 128

	private val ring = SpaceShapes.ring(
		centerX = CENTER_X,
		centerZ = CENTER_Z,
		planeY = PLANE_Y,
		innerRadius = 400.0,
		outerRadius = 460.0,
		verticalThickness = 3,
	)

	val group = CheckGroup(

		name = "spacegen",
		description = "asteroid ring geometry and deterministic scatter",

	).apply {

		//////////////////
		///// RING BOUNDS
		//////////////////

		check("a point inside the annulus is claimed") {
			require(ring.contains(430, PLANE_Y, 0), "midpoint of the band was not claimed")
		}

		check("a point inside the inner radius is not claimed") {
			require(!ring.contains(399, PLANE_Y, 0), "cell inside the hole was claimed")
		}

		check("a point outside the outer radius is not claimed") {
			require(!ring.contains(461, PLANE_Y, 0), "cell beyond the rim was claimed")
		}

		check("both radii are inclusive") {

			require(ring.contains(400, PLANE_Y, 0), "inner radius excluded")
			require(ring.contains(460, PLANE_Y, 0), "outer radius excluded")

		}

		check("the ring holds at a fixed distance in every direction") {

			// the whole point of a ring: distance from centre decides, not axis
			for (degrees in 0 until 360 step 5) {

				val radians = Math.toRadians(degrees.toDouble())
				val blockX = (cos(radians) * 430.0).roundToInt()
				val blockZ = (sin(radians) * 430.0).roundToInt()

				require(ring.contains(blockX, PLANE_Y, blockZ), "gap in the ring at $degrees degrees ($blockX, $blockZ)")

			}

		}

		check("vertical thickness is a half height") {

			require(ring.contains(430, PLANE_Y - 3, 0), "bottom of the band excluded")
			require(ring.contains(430, PLANE_Y + 3, 0), "top of the band excluded")
			require(!ring.contains(430, PLANE_Y - 4, 0), "cell below the band claimed")
			require(!ring.contains(430, PLANE_Y + 4, 0), "cell above the band claimed")

		}

		check("the ring is offset by its centre") {

			val offset = SpaceShapes.ring(1000, -2000, PLANE_Y, 400.0, 460.0, 3)

			require(offset.contains(1430, PLANE_Y, -2000), "offset ring missing its own midpoint")
			require(!offset.contains(430, PLANE_Y, 0), "offset ring claimed the origin ring's cell")

		}

		////////////
		///// NOISE
		////////////

		check("position noise is deterministic") {

			val first = SpaceShapes.positionNoise(123, 45, -678, 99L)
			val second = SpaceShapes.positionNoise(123, 45, -678, 99L)

			requireEquals(first, second, "same position and seed")

		}

		check("position noise stays in range") {

			for (sample in 0 until 5_000) {

				val value = SpaceShapes.positionNoise(sample * 31, sample % 256, -sample * 17, 4L)
				require(value >= 0.0 && value < 1.0, "noise out of range at sample $sample: $value")

			}

		}

		check("position noise varies with each coordinate") {

			val base = SpaceShapes.positionNoise(10, 20, 30, 1L)

			require(base != SpaceShapes.positionNoise(11, 20, 30, 1L), "x does not affect the noise")
			require(base != SpaceShapes.positionNoise(10, 21, 30, 1L), "y does not affect the noise")
			require(base != SpaceShapes.positionNoise(10, 20, 31, 1L), "z does not affect the noise")
			require(base != SpaceShapes.positionNoise(10, 20, 30, 2L), "seed does not affect the noise")

		}

		check("position noise spreads across the range") {

			// a hash that clusters would make density unpredictable
			val buckets = IntArray(10)

			for (sample in 0 until 10_000) {
				val value = SpaceShapes.positionNoise(sample, sample / 16, sample * 7, 77L)
				buckets[(value * 10).toInt().coerceIn(0, 9)]++
			}

			for ((index, count) in buckets.withIndex()) {
				require(count in 700..1300, "bucket $index held $count of 10000, expected roughly 1000")
			}

		}

		//////////////
		///// SCATTER
		//////////////

		check("scatter never claims a cell outside its base shape") {

			val scattered = SpaceShapes.scattered(ring, 1.0, 5L)

			require(!scattered.contains(399, PLANE_Y, 0), "scatter escaped the inner radius")
			require(!scattered.contains(461, PLANE_Y, 0), "scatter escaped the outer radius")
			require(!scattered.contains(430, PLANE_Y + 9, 0), "scatter escaped the band height")

		}

		check("scatter at full density is the base shape") {

			val scattered = SpaceShapes.scattered(ring, 1.0, 5L)

			for (degrees in 0 until 360 step 15) {

				val radians = Math.toRadians(degrees.toDouble())
				val blockX = (cos(radians) * 430.0).roundToInt()
				val blockZ = (sin(radians) * 430.0).roundToInt()

				requireEquals(
					ring.contains(blockX, PLANE_Y, blockZ),
					scattered.contains(blockX, PLANE_Y, blockZ),
					"full density scatter at $degrees degrees",
				)

			}

		}

		check("scatter at zero density is empty") {

			val scattered = SpaceShapes.scattered(ring, 0.0, 5L)
			require(!scattered.contains(430, PLANE_Y, 0), "zero density still claimed a cell")

		}

		check("scatter density is roughly honoured") {

			val scattered = SpaceShapes.scattered(ring, 0.25, 5L)
			var claimed = 0
			var candidates = 0

			for (blockX in 400..460) for (blockZ in -30..30) {

				if (!ring.contains(blockX, PLANE_Y, blockZ)) continue

				candidates++
				if (scattered.contains(blockX, PLANE_Y, blockZ)) claimed++

			}

			require(candidates > 500, "not enough candidate cells to measure density")

			val measured = claimed.toDouble() / candidates
			requireNear(0.25, measured, 0.05, "measured scatter density")

		}

		check("scatter is stable across repeated calls") {

			val scattered = SpaceShapes.scattered(ring, 0.3, 5L)
			val first = (400..460).map { blockX -> scattered.contains(blockX, PLANE_Y, 0) }
			val second = (400..460).map { blockX -> scattered.contains(blockX, PLANE_Y, 0) }

			requireEquals(first, second, "repeated scatter sampling")

		}

		//////////////////
		///// COMBINATORS
		//////////////////

		check("sphere claims its interior and nothing beyond") {

			val sphere = SpaceShapes.sphere(0, 64, 0, 10.0)

			require(sphere.contains(0, 64, 0), "centre excluded")
			require(sphere.contains(0, 74, 0), "surface excluded")
			require(!sphere.contains(0, 75, 0), "cell beyond the radius claimed")

		}

		check("union claims either side") {

			val left = SpaceShapes.sphere(0, 64, 0, 5.0)
			val right = SpaceShapes.sphere(100, 64, 0, 5.0)
			val both = SpaceShapes.union(left, right)

			require(both.contains(0, 64, 0), "left lobe missing")
			require(both.contains(100, 64, 0), "right lobe missing")
			require(!both.contains(50, 64, 0), "gap between lobes was claimed")

		}

		check("intersect claims only the overlap") {

			val left = SpaceShapes.sphere(0, 64, 0, 10.0)
			val right = SpaceShapes.sphere(8, 64, 0, 10.0)
			val overlap = SpaceShapes.intersect(left, right)

			require(overlap.contains(4, 64, 0), "overlap excluded")
			require(!overlap.contains(-8, 64, 0), "left-only cell claimed")

		}

		check("subtract carves the hole") {

			val solid = SpaceShapes.sphere(0, 64, 0, 10.0)
			val hollow = SpaceShapes.subtract(solid, SpaceShapes.sphere(0, 64, 0, 5.0))

			require(hollow.contains(0, 72, 0), "shell excluded")
			require(!hollow.contains(0, 64, 0), "core was not carved out")

		}

		check("translate moves the shape") {

			val moved = SpaceShapes.translate(SpaceShapes.sphere(0, 64, 0, 5.0), 1000, 0, 0)

			require(moved.contains(1000, 64, 0), "translated centre missing")
			require(!moved.contains(0, 64, 0), "original position still claimed")

		}

		check("empty claims nothing") {

			require(!SpaceShapes.empty().contains(0, 0, 0), "empty shape claimed a cell")

		}

	}

}
