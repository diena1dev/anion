package dev.diena.anion.features.machine.machine_types.thrusters

import dev.diena.anion.extensions.div
import dev.diena.anion.extensions.rotate
import dev.diena.anion.extensions.times
import dev.diena.anion.features.custom.blocks.AnionBlocks
import dev.diena.anion.features.machine.BlockSet
import dev.diena.anion.features.machine.Machine
import dev.diena.anion.features.machine.component.MachinePort
import dev.diena.anion.features.starship.Starship
import net.minecraft.core.Vec3i
import net.minecraft.world.phys.Vec3
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.block.BlockType

/**
 * The horizontal thruster stood on end. Rows run along x, characters along z, and each slice() is one
 * course up — so the barrel that ran along x there runs along y here, one cell per slice.
 *
 * Nozzle at the bottom: this one pushes a ship up, so its exhaust leaves downward.
 */
val DEBUG_THRUSTER_UP = BlockSet.new("debug_thruster_up")

	.core('C', BlockType.IRON_BLOCK)

	.assign('I', AnionBlocks.COPPER_MACHINE_CASING)
	.assign('I', AnionBlocks.COPPER_MACHINE_DATAPORT) // used to detect redstone input
	.assign('I', AnionBlocks.COPPER_MACHINE_CONDUIT)  // powered, holds the ship at its current height

	.assign('D', BlockType.POLISHED_DEEPSLATE_STAIRS)
	.assign('d', BlockType.DIORITE_WALL)
	.assign('i', BlockType.IRON_BLOCK)

	.slice(
		"DD",
		"DD"
	)
	.slice(
		"dd",
		"dd"
	)
	.slice(
		"Ci",
		"ii"
	)
	.slice(
		"II",
		"II"
	)
	.slice(
		"II",
		"II"
	)

	.build()

// TODO: make generalized thruster class — three copies of this now, which is two more than the
//       duplication was worth when there was only the horizontal one.
/**
 * Debug Thruster that outputs differing levels of thrust based on the strength of the redstone signal
 * being input.
 *
 * A powered conduit pins the ship to the highest altitude it has reached since the conduit came on,
 * arresting downward motion at that altitude and climbing back to it after any sag. Its authority is
 * finite — a fast fall is bled off over several ticks rather than stopped dead.
 *
 * A powered dataport thrusts as normal and takes precedence on its own: the pin only ever rises, so
 * thrusting drags it up and releasing leaves the ship held wherever it coasted to.
 */
class DebugThrusterUp() : Machine("debug_thruster_up", DEBUG_THRUSTER_UP) {

	companion object {

		val RELATIVE_SMOKE_OFFSET  = Vec3i(0, -3, 0)
		val BASE_THRUSTER_VELOCITY = Vec3i(0, 1, 0)

		/** blocks per tick the plume travels. with count 0 this is what scales the direction vector. */
		const val PLUME_SPEED = -0.9

		/** vertical velocity the hold aims for while climbing back to its pin. must beat gravity. */
		const val HOLD_CLIMB_RATE = 1.5

		/** most the hold may change vertical velocity by in one tick. */
		const val HOLD_ACCELERATION = 0.15

	}

	/** y the hold is pinned to, null while nothing is holding. */
	private var heldAltitude: Int? = null

	/** true while the hold is climbing back to its pin, so the climb cannot be mistaken for a real one. */
	private var recovering = false

	override fun tick() {

		val thrust = portSignal(MachinePort.Kind.DATA)
		val holding = portSignal(MachinePort.Kind.CONDUIT) > 0

		if (!holding) {
			heldAltitude = null
			recovering = false
		}

		if (thrust <= 0 && !holding) return

		emitSmoke()

		val ship = this.starship ?: return

		// before the thrust below, so a hold never eats the velocity that same thrust just added
		if (holding) holdAltitude(ship)

		if (thrust > 0) ship.velocity.addVelocity(getDirectionalVelocity(thrust))

	}

	/** Pins the ship to the altitude it was last carried to, climbing back to it if it sags. */
	// against the sag rather than the cause of it: gravity and the whole-block latch happen together
	// inside one simulator pass, so no machine tick can land between them to prevent the step
	private fun holdAltitude(ship: Starship) {

		val target = heldAltitude ?: origin.y.also { heldAltitude = it }
		val motion = ship.velocity.velocity

		if (origin.y < target) {

			recovering = true
			approachVerticalSpeed(ship, HOLD_CLIMB_RATE)
			return

		}

		// still shedding the climb it just made. the pin must not follow a rise the hold itself caused,
		// or every recovery leaves the ship a block higher than the last one.
		if (recovering) {

			if (motion.y > 0.0) {

				approachVerticalSpeed(ship, 0.0)
				return

			}

			recovering = false

		}

		// above the pin without recovering: thrust or its coast put the ship here, so the pin follows
		if (origin.y > target) heldAltitude = origin.y

		if (motion.y < 0.0) approachVerticalSpeed(ship, 0.0)

	}

	/** Nudges the ship's vertical velocity toward [desired] by at most [HOLD_ACCELERATION]. */
	private fun approachVerticalSpeed(ship: Starship, desired: Double) {

		val step = (desired - ship.velocity.velocity.y).coerceIn(-HOLD_ACCELERATION, HOLD_ACCELERATION)
		if (step == 0.0) return

		ship.velocity.addVelocity(Vec3(0.0, step, 0.0))

	}

	override fun slowTick() {
		// no-op
	}

	/** Returns a [Vec3] that respects the rotation of the thruster. */
	// a ship only ever turns about y, so a thruster pointing along y is the same after every rotation.
	// rotate() is kept anyway so this reads identically to the horizontal one.
	private fun getDirectionalVelocity(redstonePower: Int): Vec3 {

		val direction = BASE_THRUSTER_VELOCITY.rotate(rotation)
		return Vec3(direction.x.toDouble(), direction.y.toDouble(), direction.z.toDouble())
			.div(120) // make smol
			.times(Vec3(redstonePower.toDouble(), redstonePower.toDouble(), redstonePower.toDouble())) // make beeg again

	}

	/** emits smoke in the direction the thruster cone is facing */
	private fun emitSmoke() {

		val nozzle = localToWorld(RELATIVE_SMOKE_OFFSET)
		val direction = BASE_THRUSTER_VELOCITY.rotate(rotation)

		val nozzleCentre = Location(
			this.level.world,
			nozzle.x + 0.5,
			nozzle.y + 0.5,
			nozzle.z + 0.5,
		)

		Particle.CAMPFIRE_COSY_SMOKE.builder()
			.location(nozzleCentre)
			.offset(direction.x.toDouble(), direction.y.toDouble(), direction.z.toDouble())
			.count(0)
			.extra(PLUME_SPEED)
			.spawn()

	}

}
