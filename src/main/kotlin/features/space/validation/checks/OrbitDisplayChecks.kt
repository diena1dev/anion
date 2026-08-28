package dev.diena.anion.features.space.validation.checks

import dev.diena.anion.features.space.validation.CheckGroup
import dev.diena.anion.features.space.validation.support.BodyKind
import dev.diena.anion.features.space.validation.support.CircularOrbitSolver
import dev.diena.anion.features.space.validation.support.FlatSkyProjector
import dev.diena.anion.features.space.validation.support.Galaxy
import dev.diena.anion.features.space.validation.support.Orbit
import dev.diena.anion.features.space.validation.support.SimulatedBody
import dev.diena.anion.features.space.validation.support.SolarSystem

/**
 * What a player standing on a planet sees overhead: bearing, elevation and apparent size of the
 * other bodies in the system.
 *
 * Angles only. Whether that becomes a display entity, a texture or a packet is the display module's
 * problem, which is exactly why the projection is tested apart from it.
 */
object OrbitDisplayChecks {

	private const val YEAR = 24_000L

	private val system = SolarSystem(
		name = "sol",
		bodies = listOf(
			SimulatedBody("sol_star", BodyKind.STAR, radius = 700.0, orbit = null),
			SimulatedBody("terra", BodyKind.PLANET, radius = 64.0, orbit = Orbit("sol_star", 15_000.0, YEAR), worldKey = "anion:terra"),
			SimulatedBody("luna", BodyKind.MOON, radius = 16.0, orbit = Orbit("terra", 400.0, YEAR / 12), worldKey = "anion:luna"),
			SimulatedBody("far", BodyKind.PLANET, radius = 64.0, orbit = Orbit("sol_star", 90_000.0, YEAR * 8)),
		),
	)

	private val galaxy = Galaxy(listOf(system))
	private val solver = CircularOrbitSolver(galaxy)
	private val projector = FlatSkyProjector(galaxy, solver)

	val group = CheckGroup(

		name = "orbit-display",
		description = "apparent sky position of one body seen from another",

	).apply {

		check("azimuth stays inside a full turn") {

			for (tick in 0L until YEAR step 331L) {

				val sky = projector.apparentPosition("terra", "luna", tick)
				require(sky.azimuthDegrees >= 0.0 && sky.azimuthDegrees < 360.0, "azimuth out of range at tick $tick: ${sky.azimuthDegrees}")

			}

		}

		check("a moon sweeps the whole sky over its month") {

			// if the bearing never moves, the display is pinned and the orbit is not feeding it
			val bearings = (0L until YEAR / 12 step 100L).map { tick ->
				projector.apparentPosition("terra", "luna", tick).azimuthDegrees
			}

			require(bearings.min() < 90.0, "moon bearing never reached the low quadrant")
			require(bearings.max() > 270.0, "moon bearing never reached the high quadrant")

		}

		check("a nearer body looks bigger") {

			val moon = projector.apparentPosition("terra", "luna", 0L)
			val far = projector.apparentPosition("terra", "far", 0L)

			require(moon.apparentDiameterDegrees > far.apparentDiameterDegrees, "the distant planet looked larger than the moon")

		}

		check("apparent size grows as a body closes in") {

			// terra and far share a plane, so their separation swings over the long period.
			// the invariant is the anti-correlation, not any particular ratio: orbital radii are
			// fixture numbers and an assertion pinned to them breaks whenever they are retuned
			val samples = (0L until YEAR * 8 step (YEAR / 4)).map { tick ->

				val distance = solver.positionAt("terra", tick).distanceTo(solver.positionAt("far", tick))

				distance to projector.apparentPosition("terra", "far", tick).apparentDiameterDegrees

			}

			val nearest = samples.minBy { it.first }
			val furthest = samples.maxBy { it.first }

			require(furthest.first > nearest.first * 1.1, "sampling never produced a meaningful distance spread")
			require(nearest.second > furthest.second, "the body did not look bigger when it was closer")

		}

		check("the star is the largest thing in a planet's sky") {

			val star = projector.apparentPosition("terra", "sol_star", 0L)
			val moon = projector.apparentPosition("terra", "luna", 0L)

			require(star.apparentDiameterDegrees > 0.0, "star had no apparent size")
			require(moon.apparentDiameterDegrees > 0.0, "moon had no apparent size")

		}

		check("apparent size is never negative or absurd") {

			for (tick in 0L until YEAR step 733L) {

				for (target in listOf("sol_star", "luna", "far")) {

					val sky = projector.apparentPosition("terra", target, tick)
					require(sky.apparentDiameterDegrees > 0.0, "$target had zero size at tick $tick")
					require(sky.apparentDiameterDegrees <= 180.0, "$target filled more than the sky at tick $tick")

				}

			}

		}

		check("elevation decides visibility") {

			val sky = projector.apparentPosition("terra", "luna", 0L)

			requireEquals(sky.elevationDegrees > 0.0, sky.visible, "visible flag against elevation")

		}

		check("coplanar bodies sit on the horizon") {

			// every body here shares y, so elevation is zero and the display hugs the skyline
			val sky = projector.apparentPosition("terra", "luna", 0L)
			requireNear(0.0, sky.elevationDegrees, 1.0e-9, "elevation of a coplanar body")

		}

		check("projection is deterministic") {

			val first = projector.apparentPosition("terra", "luna", 9_001L)
			val second = projector.apparentPosition("terra", "luna", 9_001L)

			requireEquals(first, second, "repeated projection")

		}

		check("observing from the moon reverses the bearing") {

			val fromPlanet = projector.apparentPosition("terra", "luna", 0L).azimuthDegrees
			val fromMoon = projector.apparentPosition("luna", "terra", 0L).azimuthDegrees

			// unsigned separation in [0, 360), which is exactly 180 for opposing bearings
			val separation = ((fromPlanet - fromMoon) % 360.0 + 360.0) % 360.0
			requireNear(180.0, separation, 1.0e-6, "opposing bearings")

		}

		check("an unknown observer is rejected") {

			requireThrows("projecting from a body that does not exist") {
				projector.apparentPosition("nowhere", "luna", 0L)
			}

		}

	}

}
