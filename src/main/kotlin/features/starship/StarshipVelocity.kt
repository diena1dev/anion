package dev.diena.anion.features.starship

import dev.diena.anion.extensions.plus
import dev.diena.anion.extensions.vec3i
import net.minecraft.world.phys.Vec3

class StarshipVelocity private constructor() {

	private lateinit var velocity: Vec3
	private lateinit var starship: Starship

	companion object {

		/** creates a new starship hitbox */
		fun new(

			starship: Starship

		) : StarshipVelocity {

			val instance = StarshipVelocity()

			instance.velocity = Vec3(0.0, 0.0, 0.0)
			instance.starship = starship

			instance.applyVelocity()

			return instance

		}

	}

	////////////////////
	///// DATA FUNCTIONS
	////////////////////

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

	fun resetVelocity() : Vec3 = Vec3(0.0, 0.0, 0.0)

	/////////////////////////////////////////////
	///// MOVEMENT FUNCTIONS (Rotation, Movement)
	/////////////////////////////////////////////

	/** apply movement based on relative velocity */
	private fun applyMovement(

		starship: Starship

	) {

		starship.move(this.velocity.vec3i)

	}

	/** apply rotation velocity */
	// TODO: IMPLEMENT ROTATIONAL VELOCITY
	private fun applyRotation() {

		// NO-OP (NYI)

	}

}
