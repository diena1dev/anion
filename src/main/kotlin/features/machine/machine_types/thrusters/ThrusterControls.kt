package dev.diena.anion.features.machine.machine_types.thrusters

import dev.diena.anion.features.machine.Machine
import dev.diena.anion.features.starship.Starship
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.phys.Vec3

/**
 * The datachannel throttle behind every thruster. Redstone is read off the ports.
 *
 * Latching lives on the calling side, so [toggled] follows whatever the mainframe latched rather than
 * keeping a second latch of its own.
 */
class ThrusterThrottle private constructor() {

	companion object {

		fun new(): ThrusterThrottle = ThrusterThrottle()

		/** the throttle runs on the same 0-15 scale redstone does */
		const val MAX_THROTTLE = 15

		/** what a thruster answers to, before any other functions its variants may add */
		val FUNCTIONS = listOf("increase_throttle", "decrease_throttle", "toggle", "reset")

	}

	var throttle = 0; private set
	var toggled = false; private set

	/** Runs [function] if it is one of these. Returns whether it was. */
	fun invoke(function: String, active: Boolean): Boolean {

		when (function) {

			"increase_throttle" -> if (active) throttle = (throttle + 1).coerceAtMost(MAX_THROTTLE)
			"decrease_throttle" -> if (active) throttle = (throttle - 1).coerceAtLeast(0)

			"toggle" -> toggled = active

			"reset" -> if (active) {

				throttle = 0
				toggled = false

			}

			else -> return false

		}

		return true

	}

	/** What the datachannel is asking for, on redstone's scale. Full burn bypasses the throttle. */
	fun power(): Int = if (toggled) MAX_THROTTLE else throttle

	fun saveTo(tag: CompoundTag) {

		tag.putInt("throttle", throttle)
		tag.putBoolean("toggled", toggled)

	}

	fun loadFrom(tag: CompoundTag) {

		throttle = tag.getIntOr("throttle", 0)
		toggled = tag.getBooleanOr("toggled", false)

	}

	fun describe(): String = "throttle=$throttle toggled=$toggled -> data power ${power()}"

}

/**
 * Altitude hold for a thruster that points along y. Pins the ship to the altitude its thruster carried it
 * to and pushes back against any drift the other way.
 */
class ThrusterHover private constructor(private val thruster: Machine, private val sign: Int) {

	companion object {

		/** [sign] is +1 for a thruster that pushes up, -1 for one that pushes down. */
		fun new(thruster: Machine, sign: Int): ThrusterHover = ThrusterHover(thruster, sign)

		/** vertical speed the hold aims for while travelling back to its pin. must beat gravity. */
		const val CLIMB_RATE = 1.5

		/** most the hold may change vertical velocity by in one tick. */
		const val ACCELERATION = 0.15

		const val FUNCTION = "hover"

	}

	/** y the hold is pinned to, null while nothing is holding. */
	private var heldAltitude: Int? = null

	/** true while the hold is traveling back to its pin, so that move cannot be mistaken for a real one. */
	private var recovering = false

	/** whether the datachannel has asked for a hover. redstone is asked for separately by the thruster. */
	var engaged = false; private set

	fun engage(active: Boolean) {
		engaged = active
	}

	/** Forgets the pin. Called the moment nothing is asking for a hold any more. */
	fun release() {

		heldAltitude = null
		recovering = false

	}

	/** Holds [ship] at the altitude this thruster last carried it to. */
	fun hold(ship: Starship) {

		val target = heldAltitude ?: thruster.origin.y.also { heldAltitude = it }
		val motion = ship.velocity.velocity

		// negative means the ship has drifted back the way this thruster pushes against
		val drift = (thruster.origin.y - target) * sign

		if (drift < 0) {

			recovering = true
			approachVerticalSpeed(ship, CLIMB_RATE * sign)

			return

		}

		// still shedding the move it just made. the pin must not follow a drift the hold itself caused,
		// or every recovery leaves the ship a block further along than the last one.
		if (recovering) {

			if (motion.y * sign > 0.0) {

				approachVerticalSpeed(ship, 0.0)
				return

			}

			recovering = false

		}

		// past the pin without recovering: thrust or its coast put the ship here, so the pin follows
		if (drift > 0) heldAltitude = thruster.origin.y

		if (motion.y * sign < 0.0) approachVerticalSpeed(ship, 0.0)

	}

	fun saveTo(tag: CompoundTag) {
		tag.putBoolean("hovering", engaged)
	}

	fun loadFrom(tag: CompoundTag) {
		engaged = tag.getBooleanOr("hovering", false)
	}

	fun describe(): String = "hover engaged=$engaged pinned to ${heldAltitude?.toString() ?: "nothing"}"

	/** Nudges the ship's vertical velocity toward [desired] by at most [ACCELERATION]. */
	private fun approachVerticalSpeed(ship: Starship, desired: Double) {

		val step = (desired - ship.velocity.velocity.y).coerceIn(-ACCELERATION, ACCELERATION)
		if (step == 0.0) return

		ship.velocity.addVelocity(Vec3(0.0, step, 0.0))

	}

}
