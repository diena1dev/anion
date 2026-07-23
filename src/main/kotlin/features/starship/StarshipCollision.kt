package dev.diena.anion.features.starship

import dev.diena.anion.extensions.blockPos
import dev.diena.anion.extensions.minus
import dev.diena.anion.extensions.plus
import dev.diena.anion.extensions.rotateRight
import net.minecraft.core.Vec3i
import org.bukkit.block.BlockFace

object StarshipCollision {

	/**
	 * [processMoveCollision] returns whether the provided Starship can be moved by the full [vectorToMoveIn],
	 * paired with the largest safe sub-vector it can actually move (equal to [vectorToMoveIn] itself when clean).
	 *
	 * @param starship       The Starship being moved.
	 * @param vectorToMoveIn The Vec3i to move the starship in.
	 *
	 * */
	fun processMoveCollision(

		vectorToMoveIn: Vec3i,
		starship: Starship,

	): Pair<Boolean, Vec3i> {

		// fast path: full vector clean, don't bother marching.
		if (isClean(vectorToMoveIn, starship)) return true to vectorToMoveIn

		return false to marchToCollision(vectorToMoveIn, starship)

	}

	/** true if none of the starship's blocks would land in a non-air, non-starship block after moving by [vectorToMoveIn]. */
	private fun isClean(vectorToMoveIn: Vec3i, starship: Starship): Boolean {

		// this takes our old hash map and shifts values in the new block map,
		// checking to see if any of the moved blocks are not air.
		// TODO: LOW PRIORITY: research building a vector matrix with either a 0 or 1 for if a
		//       given vector contains a block, then utilize java's vector optimizations to quickly compute that.
		for (vec in starship.blockHashMap.keys) {

			val vecToMoveTo = vec+vectorToMoveIn

			// if block to move to does already exist in ship, skip recheck check
			if (starship.blockHashMap[vecToMoveTo] == null) {

				// if the block IS NOT air, cannot place, fail.
				if (!starship.level.getBlockState(vecToMoveTo.blockPos).isAir) return false else continue

			} else continue

		}

		return true

	}

	/** marches [vectorToMoveIn] one lattice step at a time and returns the last sub-vector that was still clean.
	 *  this is what lets a high-velocity move (e.g. 20 blocks/tick) stop 10 blocks out instead of tunneling
	 *  through the endpoint-only check straight to a clean destination cell. */
	private fun marchToCollision(vectorToMoveIn: Vec3i, starship: Starship): Vec3i {

		val steps = maxOf(kotlin.math.abs(vectorToMoveIn.x), kotlin.math.abs(vectorToMoveIn.y), kotlin.math.abs(vectorToMoveIn.z))
		if (steps == 0) return Vec3i.ZERO

		var lastClean = Vec3i.ZERO

		for (i in 1..steps) {

			val stepVec = Vec3i(
				(vectorToMoveIn.x * i) / steps,
				(vectorToMoveIn.y * i) / steps,
				(vectorToMoveIn.z * i) / steps,
			)

			if (!isClean(stepVec, starship)) break
			lastClean = stepVec

		}

		return lastClean

	}

	// ROTATION HELPERS START

	private fun Double.toFace(): BlockFace = when (this) {

		in 0.0..90.0 -> BlockFace.SOUTH
		in 90.0..180.0 -> BlockFace.EAST
		in 180.0..270.0 -> BlockFace.NORTH
		in 270.0..360.0 -> BlockFace.WEST
		else -> throw IllegalStateException("what the fuck did you do")

	}

	private fun stepsFromTo(from: BlockFace, to: BlockFace): Int {

		var steps = 0; var cur = from
		while (cur != to && steps < 4) { cur = cur.rotateRight(); steps++ }
		return steps

	}

	private fun rotateVec(rel: Vec3i, steps: Int): Vec3i {

		var x = rel.x; var z = rel.z
		repeat(steps) { val nx = -z; z = x; x = nx }
		return Vec3i(x, rel.y, z)

	}

	// ROTATION HELPERS END

	/**
	 * [processMoveCollision] returns a Boolean if the provided Starship can be moved by the provided angle.
	 *
	 * @param starship       The Starship being moved.
	 * @param byAngle        The angle to move the starship in, in float form.
	 *
	 * */
	fun processRotationCollision(

		byAngle: Float,
		starship: Starship,

	): Boolean {

		// do NOT mutate starship.yaw here; partial-yaw accumulation is owned by StarshipMovement.rotate.
		// compute the prospective yaw locally so this stays a pure predicate.
		val oldYaw = starship.yaw                                 // alias to starship's current yaw
		val newYaw = ((oldYaw + byAngle % 360) + 360) % 360       // modulo to wraparound whatever angle we get
		if (oldYaw.toFace() == newYaw.toFace()) return true       // do not bother checking if yaw difference was not large enough

		val steps = stepsFromTo(oldYaw.toFace(), newYaw.toFace())

		for (vec in starship.blockHashMap.keys) {

			// rotate about the ship origin
			val vecToMoveTo = starship.origin + rotateVec(vec - starship.origin, steps)

			// if block to move to does already exist in ship, skip recheck check
			if (starship.blockHashMap[vecToMoveTo] == null) {

				// if the block IS NOT air, cannot place, fail.
				if (!starship.level.getBlockState(vecToMoveTo.blockPos).isAir) return false else continue

			} else continue

		}

		// if all blocks can be moved to safely, return true.
		return true

	}

}
