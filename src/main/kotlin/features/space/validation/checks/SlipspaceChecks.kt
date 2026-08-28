package dev.diena.anion.features.space.validation.checks

import dev.diena.anion.features.space.validation.CheckGroup
import dev.diena.anion.features.space.validation.support.SlipspaceNetwork
import dev.diena.anion.features.space.validation.support.SpatialAnchor
import net.minecraft.world.phys.Vec3

/**
 * Spatial anchors and the tunnels they hold open through slipspace.
 *
 * The rules under test: a tunnel needs two spun anchors tethered to each other, tethers are
 * symmetric, and anything short of that drops you into uncharted slipspace, which by definition
 * does not put you where you went in.
 */
object SlipspaceChecks {

	private fun network(): SlipspaceNetwork = SlipspaceNetwork(listOf(
		SpatialAnchor("sol_gate", "anion:terra", Vec3(0.0, 64.0, 0.0), spun = true),
		SpatialAnchor("vega_gate", "anion:vega_i", Vec3(50_000.0, 64.0, 0.0), spun = true),
		SpatialAnchor("rigel_gate", "anion:rigel", Vec3(-80_000.0, 64.0, 12_000.0), spun = true),
		SpatialAnchor("cold_gate", "anion:terra", Vec3(200.0, 64.0, 0.0), spun = false),
	))

	val group = CheckGroup(

		name = "slipspace",
		description = "spatial anchor tethering and slipspace routing",

	).apply {

		////////////////
		///// TETHERING
		////////////////

		check("an untethered anchor opens no tunnel") {

			requireEquals(null, network().exitFor("sol_gate"), "exit for a lone anchor")

		}

		check("a tethered pair opens a tunnel both ways") {

			val slipspace = network()
			slipspace.tether("sol_gate", "vega_gate")

			requireEquals("vega_gate", slipspace.exitFor("sol_gate"), "sol to vega")
			requireEquals("sol_gate", slipspace.exitFor("vega_gate"), "vega to sol")

		}

		check("an anchor cannot tether to itself") {

			requireThrows("self tether") { network().tether("sol_gate", "sol_gate") }

		}

		check("an unspun anchor cannot be tethered") {

			requireThrows("tether to a cold anchor") { network().tether("sol_gate", "cold_gate") }

		}

		check("an unknown anchor cannot be tethered") {

			requireThrows("tether to a missing anchor") { network().tether("sol_gate", "ghost_gate") }

		}

		check("duplicate anchor ids are rejected") {

			requireThrows("network with repeated ids") {
				SlipspaceNetwork(listOf(
					SpatialAnchor("gate", "anion:a", Vec3.ZERO, spun = true),
					SpatialAnchor("gate", "anion:b", Vec3.ZERO, spun = true),
				))
			}

		}

		check("re-tethering moves the far end and orphans the old one") {

			val slipspace = network()
			slipspace.tether("sol_gate", "vega_gate")
			slipspace.tether("sol_gate", "rigel_gate")

			requireEquals("rigel_gate", slipspace.exitFor("sol_gate"), "sol now routes to rigel")
			requireEquals("sol_gate", slipspace.exitFor("rigel_gate"), "rigel routes back to sol")
			requireEquals(null, slipspace.exitFor("vega_gate"), "vega should have been orphaned")

		}

		check("cutting a tether closes both ends") {

			val slipspace = network()
			slipspace.tether("sol_gate", "vega_gate")
			slipspace.cut("sol_gate")

			requireEquals(null, slipspace.exitFor("sol_gate"), "cut end still routing")
			requireEquals(null, slipspace.exitFor("vega_gate"), "far end still routing")

		}

		check("cutting an untethered anchor is harmless") {

			val slipspace = network()
			slipspace.cut("sol_gate")

			requireEquals(null, slipspace.exitFor("sol_gate"), "exit after a no-op cut")

		}

		////////////////////
		///// ROUTE INTEGRITY
		////////////////////

		check("a trade route chains through its anchors") {

			// sol -> vega and vega' -> rigel are two separate tunnels sharing a system, not one hop
			val slipspace = network()
			slipspace.tether("sol_gate", "vega_gate")

			requireEquals("vega_gate", slipspace.exitFor("sol_gate"), "first leg")
			requireEquals(null, slipspace.exitFor("rigel_gate"), "rigel is not on this route")

		}

		check("a tunnel is reversible") {

			val slipspace = network()
			slipspace.tether("sol_gate", "rigel_gate")

			val out = slipspace.exitFor("sol_gate")!!
			val back = slipspace.exitFor(out)

			requireEquals("sol_gate", back, "return leg")

		}

		//////////////////////
		///// UNCHARTED SPACE
		//////////////////////

		check("uncharted slipspace does not put you where you went in") {

			val slipspace = network()
			val entry = slipspace.anchor("sol_gate")!!.position
			val exit = slipspace.uncharted("sol_gate", 42L)

			require(entry.distanceTo(exit) > 1_000.0, "uncharted exit landed on top of the entry")

		}

		check("uncharted exits are deterministic in the seed") {

			val slipspace = network()

			requireEquals(
				slipspace.uncharted("sol_gate", 7L),
				slipspace.uncharted("sol_gate", 7L),
				"repeated uncharted transit",
			)

		}

		check("a different seed comes out somewhere else") {

			val slipspace = network()

			require(
				slipspace.uncharted("sol_gate", 7L) != slipspace.uncharted("sol_gate", 8L),
				"seed had no effect on the uncharted exit",
			)

		}

		check("two anchors do not share an uncharted exit") {

			val slipspace = network()

			require(
				slipspace.uncharted("sol_gate", 7L) != slipspace.uncharted("vega_gate", 7L),
				"different anchors landed in the same place",
			)

		}

		check("an unknown anchor cannot be entered") {

			requireThrows("uncharted transit from a missing anchor") { network().uncharted("ghost_gate", 1L) }

		}

	}

}
