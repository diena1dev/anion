package dev.diena.anion.features.starship

import dev.diena.anion.extensions.floorVec3i
import dev.diena.anion.extensions.minus
import dev.diena.anion.extensions.plus
import net.minecraft.core.Vec3i
import net.minecraft.world.phys.Vec3

class StarshipVelocity private constructor() {

	lateinit var velocity: Vec3 private set
	private lateinit var starship: Starship

	/**
	 * fractional leftover between whole-block moves.
	 * TODO: refactor starship coordinate storage to merge coordinate handling back into Starship.
	 * */
	var subBlockOffset: Vec3 = Vec3.ZERO

	/** whole-block step latched by [beginTick] and consumed by [applyVelocity]. */
	var pendingStep: Vec3i = Vec3i.ZERO

	/** outstanding holds taken by [pause]. */
	private var pauseCount = 0

	/** true while something outside the simulation owns this ship's motion. */
	val paused get() = pauseCount > 0

	companion object {

		/** creates a new starship hitbox */
		fun new(

			starship: Starship

		) : StarshipVelocity {

			val instance = StarshipVelocity()

			instance.velocity = Vec3(0.0, 0.0, 0.0)
			instance.starship = starship

			return instance

		}

	}

	////////////////////
	///// DATA FUNCTIONS
	////////////////////

	/** folds this tick's velocity into [subBlockOffset] and latches the whole-block step the ship will attempt.
	 *  must run after all forces are applied for the tick and before [applyVelocity]. */
	fun beginTick() {

		this.subBlockOffset += this.velocity
		this.pendingStep = this.subBlockOffset.floorVec3i

	}

	fun applyVelocity() {

		applyMovement(starship) // apply movement to the starship based on
		applyRotation() // NYI, no-op

	}

	/** add a vector to the current velocity  */
	fun addVelocity(

		vec: Vec3,

	) : Vec3 {

		this.velocity = this.velocity.plus(vec)
		return this.velocity

	}

	/** overwrite velocity directly. */
	fun setVelocity(vec: Vec3) : Vec3 {

		this.velocity = vec
		return this.velocity

	}

	/** copy motion state from [other] into this instance. used when a detached piece inherits its parent's
	 *  momentum: each ship must keep its OWN [StarshipVelocity] (whose [starship] back-reference points at
	 *  itself) — aliasing the parent's instance makes this ship's [applyMovement] move the parent instead, so
	 *  the detached piece never moves and both ships fight over one shared [subBlockOffset]. */
	fun copyFrom(other: StarshipVelocity) {

		this.velocity = other.velocity
		this.subBlockOffset = other.subBlockOffset

	}

	/** clamps the latched [pendingStep] down to [safeDistance]. used by
	 *  [dev.diena.anion.features.starship.simluated.StarshipSimulator] when a collision check finds the ship
	 *  can't travel its full intended distance. */
	fun clampStep(safeDistance: Vec3i) {

		val clearX = safeDistance.x == this.pendingStep.x
		val clearY = safeDistance.y == this.pendingStep.y
		val clearZ = safeDistance.z == this.pendingStep.z

		// on a blocked axis the ship hit something: both the velocity and the accumulated fraction on that axis
		// are spent into the collision rather than carried into the next tick. without this a grounded ship
		// builds unbounded downward velocity that never gets used.
		this.velocity = Vec3(
			if (clearX) this.velocity.x else 0.0,
			if (clearY) this.velocity.y else 0.0,
			if (clearZ) this.velocity.z else 0.0,
		)

		// a blocked axis' offset is pinned to the whole-block safe distance, so applyMovement's subtraction
		// leaves exactly zero fraction behind on that axis.
		this.subBlockOffset = Vec3(
			if (clearX) this.subBlockOffset.x else safeDistance.x.toDouble(),
			if (clearY) this.subBlockOffset.y else safeDistance.y.toDouble(),
			if (clearZ) this.subBlockOffset.z else safeDistance.z.toDouble(),
		)

		this.pendingStep = safeDistance

	}

	/** takes a hold on this ship's motion. velocity and sub-block offset are left untouched while paused. */
	fun pause() {

		this.pauseCount++

	}

	/** releases one hold taken by [pause]. */
	fun resume() {

		if (this.pauseCount > 0) this.pauseCount--

	}

	fun resetVelocity() : Vec3 {

		this.velocity = Vec3(0.0, 0.0, 0.0)
		this.subBlockOffset = Vec3.ZERO
		this.pendingStep = Vec3i.ZERO
		return this.velocity

	}

	/////////////////////////////////////////////
	///// MOVEMENT FUNCTIONS (Rotation, Movement)
	/////////////////////////////////////////////

	/** apply movement based on relative velocity */
	private fun applyMovement(

		starship: Starship

	) {

		val step = this.pendingStep
		this.pendingStep = Vec3i.ZERO

		// don't execute move if we're not going to move
		if (step == Vec3i.ZERO) return

		// consume the step out of the accumulator up front, so a step is never applied twice.
		this.subBlockOffset -= Vec3(step.x.toDouble(), step.y.toDouble(), step.z.toDouble())

		// collision clamping should have already made this reachable; if it still failed the ship is wedged,
		// and carrying the leftover forward would just re-attempt the same blocked move every tick.
		if (!starship.move(step)) {

			this.velocity = Vec3(0.0, 0.0, 0.0)
			this.subBlockOffset = Vec3.ZERO

		}

	}

	/** apply rotation velocity */
	// TODO: IMPLEMENT ROTATIONAL VELOCITY
	private fun applyRotation() {

		// NO-OP (NYI)

	}

}
