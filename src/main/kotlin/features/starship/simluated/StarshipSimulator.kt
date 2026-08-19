package dev.diena.anion.features.starship.simluated

import dev.diena.anion.features.starship.Starship
import dev.diena.anion.features.starship.StarshipCollision
import net.minecraft.core.Vec3i
import net.minecraft.world.phys.Vec3
import net.minecraft.world.level.block.state.BlockState
import org.bukkit.block.Block
import org.bukkit.block.BlockType

/**
 * ```
 * |- Simulate (call to StarshipSimulator)
 * |  |- Update Velocity Modifiers (If in gravity, apply gravity; if in atmosphere, apply drag)
 * |  |- Update Velocity Sources (If present, thrusters (or debug velocity))
 * |  |
 * |  |- Modify World Block(s) (If applicable)
 * |  \- Modify Starship(s) (If applicable)
 * |     |- Modify Blocks (deforming, destruction)
 * |     \- Modify Velocity (transfer velocity proportionate to collision and mass)
 * ```
 */
class StarshipSimulator private constructor() {

	lateinit var starship: Starship
	var starshipMass: Int = 0

	/**
	 *  debug-only: constant velocity re-applied every slowTick, standing in for thrusters until those exist.
	 *  applied on the same cadence as [applyPlanetGravity] so the two can cancel exactly (a +0.5 debug against
	 *  -0.5 gravity holds a ship suspended).
	 */
	private var debugConstantVelocity: Vec3 = Vec3.ZERO

	/** the debug constant velocity this ship is actually re-applying. read-only; mutate via
	 *  [setDebugConstantVelocity] / [resetDebugConstantVelocity]. */
	val debugVelocity: Vec3 get() = this.debugConstantVelocity

	companion object {

		/** creates a new starship hitbox */
		fun new(

			starship: Starship

		) : StarshipSimulator {

			val instance = StarshipSimulator()

			instance.starship = starship

			return instance

		}

	}

	///// MAIN FUNCTIONS

	fun simulate() {

		// forces first, then latch the whole-block step they produced, then clamp that step to what actually fits.
		applyPlanetGravity()
		applyDebugConstantVelocity()
		this.starship.velocity.beginTick()
		clampVelocityToCollision()

	}

	//////////////////////////////////////////////////////////////////////
	///// ENVIRONMENTAL VELOCITY MODIFIERS (Planet Surface, Space Objects)
	//////////////////////////////////////////////////////////////////////

	private fun applyPlanetGravity() {

		val level = this.starship.level
		val velocity = this.starship.velocity

		// jank jank jank bad
		// FIXME: unjank with world API
		if (!level.bukkitName.endsWith("_space")) velocity.addVelocity(Vec3(0.0, -0.5, 0.0))

	}

	//////////////////////////////////////////////////
	///// VELOCITY SOURCES (Thrusters, Debug Velocity)
	//////////////////////////////////////////////////

	/** set the debug constant velocity, re-applied every slowTick until reset. for testing movement without thrusters. */
	fun setDebugConstantVelocity(vec: Vec3) {

		this.debugConstantVelocity = vec

	}

	/** stop re-applying debug constant velocity. does not touch the starship's current velocity. */
	fun resetDebugConstantVelocity() {

		this.debugConstantVelocity = Vec3.ZERO

	}

	private fun applyDebugConstantVelocity() {

		if (this.debugConstantVelocity == Vec3.ZERO) return

		this.starship.velocity.addVelocity(this.debugConstantVelocity)

	}

	/** clamps velocity down to the largest distance the starship can actually move this tick.
	 *  without this, velocity (e.g. from gravity) keeps accumulating every tick the ship is blocked,
	 *  so a ship sitting on the ground would build up unbounded downward velocity that never gets used,
	 *  and a fast-moving ship could tunnel past a collision that's within its full velocity but not adjacent. */
	private fun clampVelocityToCollision() {

		val intendedMove = this.starship.velocity.pendingStep
		if (intendedMove == Vec3i.ZERO) return

		val (canMoveFull, safeDistance) = StarshipCollision.processMoveCollision(intendedMove, this.starship)
		if (canMoveFull) return

		// TODO: this is where hit-transfer belongs (deal damage/mass-transfer to the block or starship
		//       we collided with, proportional to the velocity we didn't get to use).
		this.starship.velocity.clampStep(safeDistance)

	}

	//////////////////////
	///// HELPER FUNCTIONS
	//////////////////////

	// TODO: breakout into single function call so we don't iterate over the same array twice (merge mass adding logic into starship detection loop)
	/** recomputes mass from scratch. must not accumulate onto the previous value: this is called again after a
	 *  split strips blocks off the ship, where accumulating would grow the mass of a ship that just got smaller. */
	fun calculateTotalStarshipMass() {

		var total = 0

		this.starship.blockHashMap.forEach { (_, state) ->

			val simulatedBlock = BlockLists.getSimulatedBlock(state.bukkitMaterial.asBlockType() ?: BlockType.AIR)
			total += simulatedBlock.mass

		}

		this.starshipMass = total

	}

	/** Call when removing a block from a starship */
	fun removeStarshipMass(block: Block) {

		val simulatedBlock = BlockLists.getSimulatedBlock(block.type.asBlockType() ?: BlockType.AIR)
		starshipMass -= simulatedBlock.mass

	}

	/** Call when adding a block to a starship */
	fun addStarshipMass(block: Block) {

		val simulatedBlock = BlockLists.getSimulatedBlock(block.type.asBlockType() ?: BlockType.AIR)
		starshipMass += simulatedBlock.mass

	}

}
