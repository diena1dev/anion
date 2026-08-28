package dev.diena.anion.features.space.validation.support

import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Reference fixtures for the galaxy model, written so the checks in SolarSystemChecks and
 * SlipspaceChecks have something to run against before the real classes exist.
 *
 * The checks target the interfaces, not these classes. When the real model lands, either implement
 * the interface on it or write an adapter; the checks carry over untouched.
 */

///////////////
///// CONTRACTS
///////////////

/** where a body sits in system space at a given tick. */
fun interface OrbitSolver {

	fun positionAt(

		body: String,
		tick: Long,

	): Vec3

}

/** where a body appears in the sky of an observer standing on another body. */
fun interface SkyProjector {

	fun apparentPosition(

		observer: String,
		target: String,
		tick: Long,

	): SkyPosition

}

/** which system owns a world, and what a system contains. */
interface SystemIndex {

	fun systemOfWorld(worldKey: String): String?

	fun bodiesIn(system: String): List<String>

	fun systemOfBody(body: String): String?

}

/** where a slipspace transit comes out. */
interface SlipRouting {

	/** the anchor a transit from [anchor] arrives at, or null when no stable tunnel exists. */
	fun exitFor(anchor: String): String?

	/** where an untethered plunge into slipspace comes out. never the entry position. */
	fun uncharted(anchor: String, seed: Long): Vec3

}

/**
 * Azimuth and elevation in degrees, plus apparent angular diameter. Elevation below zero means the
 * body is under the observer's horizon and should not be displayed.
 */
data class SkyPosition(

	val azimuthDegrees: Double,
	val elevationDegrees: Double,
	val apparentDiameterDegrees: Double,

) {

	val visible get() = elevationDegrees > 0.0

}

////////////
///// MODEL
////////////

enum class BodyKind { STAR, PLANET, MOON, RING, ANOMALY }

/**
 * Orbital parameters. [primary] is the body this one orbits, null for the system's anchor body.
 * [phase] is a fraction of a full revolution at tick zero.
 */
data class Orbit(

	val primary: String?,
	val radius: Double,
	val periodTicks: Long,
	val phase: Double = 0.0,

)

/** data only. rendering lives in a separate display module, per the layout note. */
data class SimulatedBody(

	val id: String,
	val kind: BodyKind,
	val radius: Double,
	val orbit: Orbit?,
	val worldKey: String? = null,

)

class SolarSystem(

	val name: String,
	val bodies: List<SimulatedBody>,

) {

	private val byId = bodies.associateBy { it.id }

	init {

		require(bodies.isNotEmpty()) { "solar system $name has no bodies" }
		require(bodies.size == byId.size) { "solar system $name has duplicate body ids" }
		require(bodies.any { it.kind == BodyKind.STAR }) { "solar system $name has no star" }

		for (body in bodies) {

			val primary = body.orbit?.primary ?: continue
			require(primary in byId) { "body ${body.id} orbits $primary, which is not in $name" }

		}

	}

	fun body(id: String): SimulatedBody? = byId[id]

}

class Galaxy(

	val systems: List<SolarSystem>,

) : SystemIndex {

	private val systemByWorld = mutableMapOf<String, String>()
	private val systemByBody = mutableMapOf<String, String>()

	init {

		for (system in systems) for (body in system.bodies) {

			require(systemByBody.put(body.id, system.name) == null) { "body ${body.id} is in two systems" }

			val worldKey = body.worldKey ?: continue
			require(systemByWorld.put(worldKey, system.name) == null) { "world $worldKey is in two systems" }

		}

	}

	override fun systemOfWorld(worldKey: String): String? = systemByWorld[worldKey]

	override fun systemOfBody(body: String): String? = systemByBody[body]

	override fun bodiesIn(system: String): List<String> =
		systems.firstOrNull { it.name == system }?.bodies?.map { it.id } ?: emptyList()

	fun body(id: String): SimulatedBody? = systems.firstNotNullOfOrNull { it.body(id) }

}

//////////////////////////
///// REFERENCE SOLVERS
//////////////////////////

/** Circular coplanar orbits, resolved recursively so a moon rides its planet. */
class CircularOrbitSolver(

	private val galaxy: Galaxy,

) : OrbitSolver {

	override fun positionAt(

		body: String,
		tick: Long,

	): Vec3 {

		val simulated = galaxy.body(body) ?: throw IllegalArgumentException("unknown body $body")
		val orbit = simulated.orbit ?: return Vec3.ZERO

		val primaryPosition = orbit.primary?.let { positionAt(it, tick) } ?: Vec3.ZERO

		// tick / period is revolutions elapsed; phase shifts the starting point
		val revolutions = tick.toDouble() / orbit.periodTicks.toDouble() + orbit.phase
		val angle = revolutions * 2.0 * PI

		return Vec3(
			primaryPosition.x + cos(angle) * orbit.radius,
			primaryPosition.y,
			primaryPosition.z + sin(angle) * orbit.radius,
		)

	}

}

/**
 * Projects one body into another's sky. The observer is treated as standing at the sub-target point
 * of their own body, so elevation is the angle above the local horizon plane.
 */
class FlatSkyProjector(

	private val galaxy: Galaxy,
	private val solver: OrbitSolver,

) : SkyProjector {

	override fun apparentPosition(

		observer: String,
		target: String,
		tick: Long,

	): SkyPosition {

		val observerBody = galaxy.body(observer) ?: throw IllegalArgumentException("unknown body $observer")
		val targetBody = galaxy.body(target) ?: throw IllegalArgumentException("unknown body $target")

		val observerPosition = solver.positionAt(observer, tick)
		val targetPosition = solver.positionAt(target, tick)

		val deltaX = targetPosition.x - observerPosition.x
		val deltaY = targetPosition.y - observerPosition.y
		val deltaZ = targetPosition.z - observerPosition.z

		val horizontal = hypot(deltaX, deltaZ)
		val distance = sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ)

		val azimuth = (Math.toDegrees(atan2(deltaZ, deltaX)) + 360.0) % 360.0

		// bodies closer than the observer's own radius would be inside them, guard the divide
		val elevation = if (distance <= observerBody.radius) 90.0 else Math.toDegrees(atan2(deltaY, horizontal))
		val apparentDiameter = if (distance <= 0.0) 180.0 else Math.toDegrees(2.0 * kotlin.math.atan(targetBody.radius / distance))

		return SkyPosition(azimuth, elevation, apparentDiameter)

	}

}

///////////////
///// SLIPSPACE
///////////////

data class SpatialAnchor(

	val id: String,
	val worldKey: String,
	val position: Vec3,
	/** rings spun up: the normalspace boundary at this end is weakened */
	val spun: Boolean,

)

/**
 * Tethers between spun anchors. A tunnel exists only when both ends are spun and tethered to each
 * other; anything else drops into uncharted slipspace.
 */
class SlipspaceNetwork(

	anchors: List<SpatialAnchor>,

) : SlipRouting {

	private val byId = anchors.associateBy { it.id }
	private val tethers = mutableMapOf<String, String>()

	init {
		require(anchors.size == byId.size) { "duplicate anchor ids" }
	}

	fun anchor(id: String): SpatialAnchor? = byId[id]

	/** Tethers two anchors together. Symmetric, and both ends have to be spun up. */
	fun tether(

		first: String,
		second: String,

	) {

		require(first != second) { "an anchor cannot tether to itself" }

		val firstAnchor = byId[first] ?: throw IllegalArgumentException("unknown anchor $first")
		val secondAnchor = byId[second] ?: throw IllegalArgumentException("unknown anchor $second")

		require(firstAnchor.spun) { "anchor $first is not spun up" }
		require(secondAnchor.spun) { "anchor $second is not spun up" }

		// an anchor holds one tunnel open at a time, so re-tethering has to release the old far end
		// rather than leave it pointing at an anchor that no longer points back
		cut(first)
		cut(second)

		tethers[first] = second
		tethers[second] = first

	}

	fun cut(anchor: String) {

		val other = tethers.remove(anchor) ?: return
		tethers.remove(other)

	}

	override fun exitFor(anchor: String): String? {

		val other = tethers[anchor] ?: return null

		// a tunnel collapses the moment either end stops spinning
		if (byId[anchor]?.spun != true || byId[other]?.spun != true) return null

		return other

	}

	/** Non-euclidean: deterministic in the seed, but unrelated to where you went in. */
	override fun uncharted(

		anchor: String,
		seed: Long,

	): Vec3 {

		val entry = byId[anchor] ?: throw IllegalArgumentException("unknown anchor $anchor")

		val noiseX = SpaceShapes.positionNoise(anchor.hashCode(), 0, 0, seed)
		val noiseY = SpaceShapes.positionNoise(0, anchor.hashCode(), 0, seed)
		val noiseZ = SpaceShapes.positionNoise(0, 0, anchor.hashCode(), seed)

		val scatter = 1_000_000.0

		return Vec3(
			entry.position.x + (noiseX - 0.5) * scatter,
			64.0 + noiseY * 128.0,
			entry.position.z + (noiseZ - 0.5) * scatter,
		)

	}

}
