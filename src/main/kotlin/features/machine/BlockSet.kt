package dev.diena.anion.features.machine

import dev.diena.anion.extensions.minus
import dev.diena.anion.features.custom.blocks.AnionBlock
import dev.diena.anion.features.custom.blocks.AnionBlocks
import net.minecraft.core.Vec3i
import org.bukkit.Material
import org.bukkit.Note
import org.bukkit.block.BlockType
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.NoteBlock

/**
 * One acceptable fill for a single [BlockSet] cell. Matches on block identity only — incidental
 * state (powered, waterlogged, ...) is never compared.
 */
sealed interface BlockMatcher {

	/** stable id, stored in a machine's resolved structure and read back on load */
	val key: String

	fun matches(blockData: BlockData): Boolean

	/** a block that satisfies this matcher. for previews and debug placement, never for comparison. */
	fun representative(): BlockData

	/** an [AnionBlock], identified by the note block instrument+note pair that encodes it */
	data class Custom(val block: AnionBlock) : BlockMatcher {

		override val key: String get() = "anion/${block.namespacedKey.key}"

		override fun matches(blockData: BlockData): Boolean {

			val noteBlock = blockData as? NoteBlock ?: return false

			return AnionBlocks.fromState(noteBlock.instrument, noteBlock.note.id.toInt()) === block

		}

		override fun representative(): BlockData {

			val noteBlock = BlockType.NOTE_BLOCK.createBlockData() as NoteBlock
			noteBlock.instrument = block.instrument
			noteBlock.note = Note(block.note)

			return noteBlock

		}

	}

	/** a vanilla block, identified by material alone */
	data class Vanilla(val type: BlockType) : BlockMatcher {

		private val material: Material = type.createBlockData().material

		override val key: String get() = "vanilla/${material.name}"

		override fun matches(blockData: BlockData): Boolean = blockData.material == material

		override fun representative(): BlockData = type.createBlockData()

	}

}

/**
 * Structure of a Machine! Defines the bounds and the origin point of the Machine's structure.
 * The origin point is used in when assembling a Machine with a wrench, usually occupied by a Machine "Core" tiered block.
 * */
open class BlockSet private constructor(

	val name: String,
	/** every acceptable variant per offset — a cell matches if the world block matches ANY entry */
	val blockMap: Map<Vec3i, List<BlockMatcher>>,

) {

	companion object {

		/** returns Builder */
		fun new(name: String): Builder = Builder(name)

	}

	class Builder(

		val name: String,

	) {

		// internal values
		private var blockMap: MutableMap<Vec3i, List<BlockMatcher>> = mutableMapOf()
		// multiple blocks can be assigned to the same char — any of them is accepted at that position
		private var charMatchers: MutableMap<Char, MutableList<BlockMatcher>> = mutableMapOf()
		private var coreChar: Char? = null
		private var coreOffset: Vec3i? = null

		// machine structure definition
		var width: Int = 0
		var length: Int = 0
		var height: Int = 0


		/**
		 *  holy FUCK astral is so much smarter than i am
		 *
		 *  astral if you read this i swear i have not looked at MachineType in the past two months....
		 *  i took great care to make of this myself, even if it's functionally the same for some bits-
		 *  i actually adapted the spigot code for ShapedRecipe (really just the fact that they used a vararg)
		 * */

		/** run final checks and return structure */
		fun build(): BlockSet {

			val declaredCoreChar = this.coreChar
				?: throw IllegalStateException("attempted to build a MachineStructure without a core assignment. fix your registrations!")
			val offset = this.coreOffset
				?: throw IllegalStateException("core char '$declaredCoreChar' never appears in any slice of '$name'. fix your registrations!")

			// re-center every entry so the core's own cell lands on (0, 0, 0) — that's the
			// pivot assemble()/isIntact() actually measure every other offset against.
			val recentered = blockMap.mapKeys { (pos, _) -> pos - offset }

			return BlockSet(
				this.name,
				recentered
			)

		}

		/**
		 * Assign Machine Core position. Callable exactly once.
		 *
		 * [block] does not have to be unique to the structure — pass a block that fills it, like the
		 * casing, and the first cell that char lands on becomes the origin. A machine only needs a
		 * dedicated core block if you want one.
		 */
		fun core(char: Char, block: AnionBlock): Builder {

			if (coreChar != null) throw IllegalStateException("duplicate core registration in machine structure")

			// the same block may already be an accepted variant for this char, and listing it twice
			// would only make resolveStructure walk a dead entry
			val matchers = charMatchers.getOrPut(char) { mutableListOf() }
			val matcher = BlockMatcher.Custom(block)
			if (matcher !in matchers) matchers.add(matcher)

			coreChar = char
			return this

		}

		fun core(char: Char, block: BlockType): Builder {

			if (coreChar != null) throw IllegalStateException("duplicate core registration in machine structure")

			// the same block may already be an accepted variant for this char, and listing it twice
			// would only make resolveStructure walk a dead entry
			val matchers = charMatchers.getOrPut(char) { mutableListOf() }
			val matcher = BlockMatcher.Vanilla(block)
			if (matcher !in matchers) matchers.add(matcher)

			coreChar = char
			return this

		}

		/** Assign AnionBlock. Call multiple times on the same char to accept any of several block variants there. */
		fun assign(char: Char, block: AnionBlock): Builder {

			charMatchers.getOrPut(char) { mutableListOf() }.add(BlockMatcher.Custom(block))
			return this

		}

		/** Assign Vanilla (Bukkit) Block. Call multiple times on the same char to accept any of several block variants there. */
		fun assign(char: Char, block: BlockType): Builder {

			charMatchers.getOrPut(char) { mutableListOf() }.add(BlockMatcher.Vanilla(block))
			return this

		}

		// vararg stands for a variable number of arguments
		/** call [slice()] *after* assigning blocks, ports, and cores. */
		fun slice(vararg shape: String): Builder {

			// shape.size tells us the total number of chars vertically (length)
			// row in shape and row length tells us number of chars horizontally (width)
			// and slice() call count tells us overall height

			// validate that all slices match the same length (array entries)
			if (this.length != 0 && shape.size != this.length) throw IllegalStateException("length is not equal across all slices!")
			this.length = shape.size

			/////////// logic start

			// rows run along x
			for ((localX, row) in shape.iterator().withIndex()) {

				// validate that all slices match the same width (array entry string length)
				if (this.width != 0 && row.length != this.width) throw IllegalStateException("width is not equal across all slices!")

				// characters within a row run along z
				for ((localZ, char) in row.toCharArray().iterator().withIndex()) {

					if (char == ' ') continue

					// gather every acceptable variant for this char — vanilla and custom can coexist
					val variants = charMatchers[char]
						?: throw IllegalStateException("block mapping missing for char '$char'")

					// record where the core actually sits in the raw grid so build() can re-center the
					// whole structure around it. the core char may repeat all over the structure, and
					// slices are walked in a fixed order, so the first cell it lands on is a
					// deterministic choice across rebuilds.
					if (char == coreChar && coreOffset == null) {
						coreOffset = Vec3i(localX, this.height, localZ)
					}

					// now finally assign the blockmap
					blockMap[Vec3i(localX, this.height, localZ)] = variants.toList()

				}

			}

			// add one to height for every slice if everything else passes
			height += 1

			/////////// logic end

			// for chaining
			return this

		}

	}

}
