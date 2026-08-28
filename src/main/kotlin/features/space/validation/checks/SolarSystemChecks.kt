package dev.diena.anion.features.space.validation.checks

import dev.diena.anion.features.space.validation.CheckGroup
import dev.diena.anion.features.space.validation.support.BodyKind
import dev.diena.anion.features.space.validation.support.CircularOrbitSolver
import dev.diena.anion.features.space.validation.support.Galaxy
import dev.diena.anion.features.space.validation.support.Orbit
import dev.diena.anion.features.space.validation.support.SimulatedBody
import dev.diena.anion.features.space.validation.support.SolarSystem
import kotlin.math.hypot

/**
 * Galaxy and solar system invariants, and the orbital solver underneath the sky displays.
 *
 * Runs against the reference fixtures in support/SpaceModel. Point [galaxy] and the solver at the
 * real classes when they exist and these carry over.
 */
object SolarSystemChecks {

	private const val YEAR = 24_000L

	private val sol = SolarSystem(
		name = "sol",
		bodies = listOf(
			SimulatedBody("sol_star", BodyKind.STAR, radius = 700.0, orbit = null),
			SimulatedBody("terra", BodyKind.PLANET, radius = 64.0, orbit = Orbit("sol_star", 15_000.0, YEAR), worldKey = "anion:terra"),
			SimulatedBody("luna", BodyKind.MOON, radius = 16.0, orbit = Orbit("terra", 400.0, YEAR / 12), worldKey = "anion:luna"),
			SimulatedBody("belt", BodyKind.RING, radius = 0.0, orbit = Orbit("sol_star", 26_000.0, YEAR * 3)),
		),
	)

	private val vega = SolarSystem(
		name = "vega",
		bodies = listOf(
			SimulatedBody("vega_star", BodyKind.STAR, radius = 900.0, orbit = null),
			SimulatedBody("vega_i", BodyKind.PLANET, radius = 48.0, orbit = Orbit("vega_star", 9_000.0, YEAR / 2), worldKey = "anion:vega_i"),
		),
	)

	private val galaxy = Galaxy(listOf(sol, vega))
	private val solver = CircularOrbitSolver(galaxy)

	val group = CheckGroup(

		name = "solar-system",
		description = "galaxy and system model invariants, orbital solving",

	).apply {

		////////////////////
		///// MODEL SHAPE
		////////////////////

		check("a system must contain a star") {

			requireThrows("system with only planets") {
				SolarSystem("starless", listOf(SimulatedBody("rock", BodyKind.PLANET, 10.0, null)))
			}

		}

		check("a system rejects duplicate body ids") {

			requireThrows("system with a repeated id") {
				SolarSystem(
					"twins",
					listOf(
						SimulatedBody("star", BodyKind.STAR, 100.0, null),
						SimulatedBody("star", BodyKind.STAR, 100.0, null),
					),
				)
			}

		}

		check("a body cannot orbit something outside its system") {

			requireThrows("planet orbiting a foreign star") {
				SolarSystem(
					"orphan",
					listOf(
						SimulatedBody("star", BodyKind.STAR, 100.0, null),
						SimulatedBody("stray", BodyKind.PLANET, 10.0, Orbit("some_other_star", 100.0, YEAR)),
					),
				)
			}

		}

		check("a galaxy rejects a world claimed by two systems") {

			val first = SolarSystem("a", listOf(
				SimulatedBody("a_star", BodyKind.STAR, 100.0, null),
				SimulatedBody("a_planet", BodyKind.PLANET, 10.0, Orbit("a_star", 100.0, YEAR), worldKey = "anion:shared"),
			))

			val second = SolarSystem("b", listOf(
				SimulatedBody("b_star", BodyKind.STAR, 100.0, null),
				SimulatedBody("b_planet", BodyKind.PLANET, 10.0, Orbit("b_star", 100.0, YEAR), worldKey = "anion:shared"),
			))

			requireThrows("galaxy with a shared world key") { Galaxy(listOf(first, second)) }

		}

		//////////////////
		///// THE INDEX
		//////////////////

		check("a world resolves to its system") {

			requireEquals("sol", galaxy.systemOfWorld("anion:terra"), "system of terra")
			requireEquals("vega", galaxy.systemOfWorld("anion:vega_i"), "system of vega i")

		}

		check("an unknown world resolves to nothing") {

			requireEquals(null, galaxy.systemOfWorld("anion:nowhere"), "system of an unknown world")

		}

		check("a body resolves to its system") {

			requireEquals("sol", galaxy.systemOfBody("luna"), "system of luna")

		}

		check("a system lists its bodies") {

			val bodies = galaxy.bodiesIn("sol")

			requireEquals(4, bodies.size, "body count in sol")
			require("belt" in bodies, "asteroid belt missing from sol")

		}

		check("bodies without a world are still tracked") {

			// the belt and the star have no walkable world, but they are still system members
			requireEquals(null, galaxy.body("belt")!!.worldKey, "belt world key")
			requireEquals("sol", galaxy.systemOfBody("belt"), "system of the belt")

		}

		//////////////////////
		///// ORBITAL SOLVING
		//////////////////////

		check("a star sits at the system origin") {

			val position = solver.positionAt("sol_star", 8_123L)

			requireNear(0.0, position.x, 1.0e-9, "star x")
			requireNear(0.0, position.z, 1.0e-9, "star z")

		}

		check("a planet keeps a constant distance from its star") {

			for (tick in 0L until YEAR step 997L) {

				val position = solver.positionAt("terra", tick)
				requireNear(15_000.0, hypot(position.x, position.z), 1.0e-6, "terra orbital radius at tick $tick")

			}

		}

		check("a planet returns to its start after one period") {

			val start = solver.positionAt("terra", 0L)
			val later = solver.positionAt("terra", YEAR)

			requireNear(start.x, later.x, 1.0e-6, "terra x after one year")
			requireNear(start.z, later.z, 1.0e-6, "terra z after one year")

		}

		check("half a period puts a planet on the far side") {

			val start = solver.positionAt("terra", 0L)
			val half = solver.positionAt("terra", YEAR / 2)

			requireNear(-start.x, half.x, 1.0e-6, "terra x at half a year")
			requireNear(-start.z, half.z, 1.0e-6, "terra z at half a year")

		}

		check("phase offsets the starting point") {

			val offsetSystem = SolarSystem("phased", listOf(
				SimulatedBody("star", BodyKind.STAR, 100.0, null),
				SimulatedBody("early", BodyKind.PLANET, 10.0, Orbit("star", 1_000.0, YEAR, phase = 0.0)),
				SimulatedBody("late", BodyKind.PLANET, 10.0, Orbit("star", 1_000.0, YEAR, phase = 0.5)),
			))

			val offsetSolver = CircularOrbitSolver(Galaxy(listOf(offsetSystem)))

			val early = offsetSolver.positionAt("early", 0L)
			val late = offsetSolver.positionAt("late", 0L)

			requireNear(-early.x, late.x, 1.0e-6, "half phase x")

		}

		check("a moon rides its planet") {

			// absolute moon position must track the planet, not the star
			for (tick in 0L until YEAR step 1_337L) {

				val planet = solver.positionAt("terra", tick)
				val moon = solver.positionAt("luna", tick)
				val separation = hypot(moon.x - planet.x, moon.z - planet.z)

				requireNear(400.0, separation, 1.0e-6, "luna separation from terra at tick $tick")

			}

		}

		check("a moon's distance from the star varies") {

			// if the moon were solved against the star this would be constant, which is the bug
			val near = hypot(solver.positionAt("luna", 0L).x, solver.positionAt("luna", 0L).z)
			val later = hypot(solver.positionAt("luna", YEAR / 24).x, solver.positionAt("luna", YEAR / 24).z)

			require(kotlin.math.abs(near - later) > 1.0, "luna distance from the star never changed, it is not orbiting terra")

		}

		check("solving is deterministic") {

			requireEquals(solver.positionAt("luna", 4_242L), solver.positionAt("luna", 4_242L), "repeated solve")

		}

		check("an unknown body is rejected") {

			requireThrows("solving a body that does not exist") { solver.positionAt("pluto", 0L) }

		}

	}

}
