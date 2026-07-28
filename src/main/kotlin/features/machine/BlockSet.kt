package dev.diena.anion.features.machine

import dev.diena.anion.extensions.minus
import dev.diena.anion.features.custom.blocks.AnionBlock
import dev.diena.anion.features.custom.blocks.AnionBlock.Companion.getBlockState
import net.minecraft.core.Vec3i
import org.bukkit.block.BlockState
import org.bukkit.block.BlockType

/**
 * Structure of a Machine! Defines the bounds and the origin point of the Machine's structure.
 * The origin point is used in when assembling a Machine with a wrench, usually occupied by a Machine "Core" tiered block.
 * */
open class BlockSet private constructor(

    val name: String,
    /** every acceptable block variant per offset — a cell matches if the world block equals ANY entry */
    val blockMap: Map<Vec3i, List<BlockState>>,

) {

    companion object {

        /** returns Builder */
        fun new(name: String): Builder = Builder(name)

    }

    class Builder(

        val name: String,

    ) {

        // internal values
        private var blockMap: MutableMap<Vec3i, MutableList<BlockState>> = mutableMapOf()
        // multiple blocks can be assigned to the same char — any of them is accepted at that position
        private var charCustomRepresentations: MutableMap<Char, MutableList<AnionBlock>> = mutableMapOf()
        private var charVanillaRepresentations: MutableMap<Char, MutableList<BlockType>> = mutableMapOf()
        private var coreChar: Char? = null
        private var coreAnionBlock: AnionBlock? = null
        private var coreOffset: Vec3i? = null

        // machine structure definition
        var width: Int = 0
        var length: Int = 0
        var height: Int = 0

        // store if we have added a core block yet
        var hasCoreBlock = false

        /**
         *  holy FUCK astral is so much smarter than i am
         *
         *  astral if you read this i swear i have not looked at MachineType in the past two months....
         *  i took great care to make of this myself, even if it's functionally the same for some bits-
         *  i actually adapted the spigot code for ShapedRecipe (really just the fact that they used a vararg)
         * */

        /** run final checks and return structure */
        fun build(): BlockSet {

            if (!hasCoreBlock) throw IllegalStateException("attempted to build a MachineStructure without a core assignment. fix your registrations!")
            val offset = this.coreOffset ?: throw IllegalStateException("coreBlock was never initialized!")

            // re-center every entry so the core's own cell lands on (0, 0, 0) — that's the
            // pivot assemble()/isIntact() actually measure every other offset against.
            val recentered = blockMap.mapKeys { (pos, _) -> pos - offset }

            return BlockSet(
                this.name,
                recentered
            )

        }

        /** Assign Machine Core position */
        fun core(char: Char, block: AnionBlock): Builder {

            if (coreChar != null) throw IllegalStateException("duplicate core registration in machine structure")
            charCustomRepresentations.getOrPut(char) { mutableListOf() }.add(block)

            coreChar = char
            coreAnionBlock = block
            return this

        }

        /** Assign AnionBlock. Call multiple times on the same char to accept any of several block variants there. */
        fun assign(char: Char, block: AnionBlock): Builder {

            charCustomRepresentations.getOrPut(char) { mutableListOf() }.add(block)
            return this

        }

        /** Assign Vanilla (Bukkit) Block. Call multiple times on the same char to accept any of several block variants there. */
        fun assign(char: Char, block: BlockType): Builder {

            charVanillaRepresentations.getOrPut(char) { mutableListOf() }.add(block)
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

            // length (x)
            for ((localX, wEntry) in shape.iterator().withIndex()) {

                // validate that all slices match the same width (array entry string length)
                if (this.width != 0 && wEntry.length != this.width) throw IllegalStateException("width is not equal across all slices!")

                // width (y)
                for ((localY, char) in wEntry.toCharArray().iterator().withIndex()) {

                    if (char == ' ') continue

                    // gather every acceptable variant for this char — vanilla and custom can coexist
                    val variants = mutableListOf<BlockState>()

                    charVanillaRepresentations[char]?.forEach { blockType ->
                        variants += blockType.createBlockData().createBlockState()
                    }

                    charCustomRepresentations[char]?.forEach { anionBlock ->
                        anionBlock.getBlockState()?.let { variants += it }
                    }

                    if (variants.isEmpty()) throw IllegalStateException("block mapping missing for char '$char'")

                    // record where the core actually sits in the raw grid so build() can
                    // re-center the whole structure around it.
                    if (char == coreChar) {
                        if (hasCoreBlock) throw IllegalStateException("duplicate core block assignment in structure")
                        hasCoreBlock = true
                        coreOffset = Vec3i(localX, this.height, localY)
                    }

                    // now finally assign the blockmap
                    blockMap[Vec3i(localX, this.height, localY)] = variants

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
