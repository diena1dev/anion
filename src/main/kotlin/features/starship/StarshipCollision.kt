package dev.diena.anion.features.starship

import dev.diena.anion.extensions.blockPos
import dev.diena.anion.extensions.plus
import net.minecraft.core.Vec3i

object StarshipCollision {

	/**
	 * [processCollision] returns a Boolean if the provided Starship can be moved in the provided Vec3i.
	 *
	 * @param starship       The Starship being moved.
	 * @param vectorToMoveIn The Vec3i to move the starship in.
	 *
	 * */
	fun processCollision(

		starship: Starship,
		vectorToMoveIn: Vec3i

	): Boolean {

		// this takes our old hash map and shifts values in the new block map,
		// checking to see if any of the moved blocks are not air.
		// TODO: do not cancel blocked movement, move as close as possible without clipping through blocks.
		for (vec in starship.blockHashMap.keys) {

			val vecToMoveTo = vec+vectorToMoveIn

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
