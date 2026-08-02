package dev.diena.anion.features.custom.blocks

import org.bukkit.block.BlockFace
import org.bukkit.block.BlockType
import org.bukkit.block.data.MultipleFacing
import org.bukkit.inventory.ItemStack

enum class MushroomType(val blockType: BlockType) {

	BROWN(BlockType.BROWN_MUSHROOM_BLOCK),
	RED(BlockType.RED_MUSHROOM_BLOCK)

}

/**
 * A mushroom block's [MultipleFacing] data has 6 independent faces
 * (up/down/north/south/east/west), giving 2^6 = 64 possible blockstates.
 * [state] (1–64) is a stable index into that space so callers don't have
 * to hand-pick a face combination.
 */
class AnionMushroomCustomBlock(

	displayName: String,
	val mushroomType: MushroomType,
	val state: Int,
	val stacksTo: Int = 64,
	drops: ItemStack? = null,

) : AnionBlock(displayName, drops = drops) {

	val faces: Set<BlockFace> = decodeState(state)

	init {
		if (state !in 1..64) throw IllegalStateException("state must be 1–64, got $state for ${this.namespacedKey}")
	}

	fun applyTo(blockData: MultipleFacing) {
		FACES.forEach { face -> blockData.setFace(face, face in faces) }
	}

	companion object {

		private val FACES = listOf(
			BlockFace.UP,
			BlockFace.DOWN,
			BlockFace.NORTH,
			BlockFace.SOUTH,
			BlockFace.EAST,
			BlockFace.WEST,
		)

		fun decodeState(state: Int): Set<BlockFace> {
			val index = state - 1
			return FACES.filterIndexed { bit, _ -> (index shr bit) and 1 == 1 }.toSet()
		}

	}

}
