package dev.diena.anion.features.starship.simluated

import dev.diena.anion.features.starship.Starship
import dev.diena.anion.features.starship.StarshipCollision
import net.minecraft.core.Vec3i
import net.minecraft.world.phys.Vec3
import net.minecraft.world.level.block.state.BlockState
import org.bukkit.Bukkit
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

	/** debug-only: constant velocity re-applied every 20 ticks, standing in for thrusters until those exist. */
	private var debugConstantVelocity: Vec3 = Vec3.ZERO

	/** game tick [debugConstantVelocity] was last applied on; -1 forces an immediate apply. slowTick runs off
	 *  a real-time (1s) async scheduler, which can drift from the actual 20-tick server cadence under lag,
	 *  so we track elapsed game ticks ourselves instead of trusting "once per slowTick" to mean "every 20 ticks". */
	private var lastDebugVelocityTick: Int = -1

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

		applyPlanetGravity()
		applyDebugConstantVelocity()
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

	/** set the debug constant velocity, re-applied every 20 ticks until reset. for testing movement without thrusters. */
	fun setDebugConstantVelocity(vec: Vec3) {

		this.debugConstantVelocity = vec
		this.lastDebugVelocityTick = -1 // force apply on next simulate() instead of waiting out a stale window

	}

	/** stop re-applying debug constant velocity. does not touch the starship's current velocity. */
	fun resetDebugConstantVelocity() {

		this.debugConstantVelocity = Vec3.ZERO
		this.lastDebugVelocityTick = -1

	}

	private fun applyDebugConstantVelocity() {

		if (this.debugConstantVelocity == Vec3.ZERO) return

		val currentTick = Bukkit.getCurrentTick()
		if (this.lastDebugVelocityTick != -1 && currentTick - this.lastDebugVelocityTick < 20) return

		this.starship.velocity.addVelocity(this.debugConstantVelocity)
		this.lastDebugVelocityTick = currentTick

	}

	/** clamps velocity down to the largest distance the starship can actually move this tick.
	 *  without this, velocity (e.g. from gravity) keeps accumulating every tick the ship is blocked,
	 *  so a ship sitting on the ground would build up unbounded downward velocity that never gets used,
	 *  and a fast-moving ship could tunnel past a collision that's within its full velocity but not adjacent. */
	private fun clampVelocityToCollision() {

		val intendedMove = this.starship.velocity.vec3i
		if (intendedMove == Vec3i.ZERO) return

		val (canMoveFull, safeDistance) = StarshipCollision.processMoveCollision(intendedMove, this.starship)
		if (canMoveFull) return

		// TODO: this is where hit-transfer belongs (deal damage/mass-transfer to the block or starship
		//       we collided with, proportional to the velocity we didn't get to use).
		this.starship.velocity.setVelocity(Vec3(safeDistance.x.toDouble(), safeDistance.y.toDouble(), safeDistance.z.toDouble()))

	}

	//////////////////////
	///// HELPER FUNCTIONS
	//////////////////////

	// TODO: breakout into single function call so we don't iterate over the same array twice (merge mass adding logic into starship detection loop)
	fun calculateTotalStarshipMass() {

		this.starship.blockHashMap.forEach { (_, state) ->

			val simulatedBlock = BlockLists.getSimulatedBlock(state.bukkitMaterial.asBlockType() ?: BlockType.AIR)
			starshipMass += simulatedBlock.mass

		}

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
