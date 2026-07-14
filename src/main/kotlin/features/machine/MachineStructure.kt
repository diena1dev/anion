package dev.diena.anion.features.machine

import dev.diena.anion.features.custom.blocks.AnionBlock
import dev.diena.anion.features.custom.blocks.AnionBlock.Companion.getBlockState
import net.minecraft.core.Vec3i
import org.bukkit.block.BlockState
import org.bukkit.block.BlockType
import org.bukkit.inventory.ShapedRecipe

/**
 * Structure of a Machine! Defines the bounds and the origin point of the Machine's structure.
 * The origin point is used in when assembling a Machine with a wrench, usually occupied by a Machine "Core" tiered block.
 * */
open class MachineStructure private constructor(

    val name: String,
    val blockMap: Map<Vec3i, BlockState>,
    val coreBlock: Pair<Vec3i, AnionBlock>,

) {

    companion object {

        /** returns Builder */
        fun new(name: String): Builder = Builder(name)

    }

    class Builder(

        val name: String,

    ) {

        // internal values
        private var blockMap: MutableMap<Vec3i, BlockState> = mutableMapOf()
        private var charCustomRepresentations: MutableMap<Char, AnionBlock> = mutableMapOf()
        private var charVanillaRepresentations: MutableMap<Char, BlockType> = mutableMapOf()
        private var coreBlock: Pair<Vec3i, AnionBlock>? = null

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
        fun build(): MachineStructure {

            if (!hasCoreBlock) throw IllegalStateException("attempted to build a MachineStructure without a core assignment. fix your registrations!")
            if (this.coreBlock == null) throw IllegalStateException("coreBlock was never initialized!")

            return MachineStructure(
                this.name,
                this.blockMap,
                this.coreBlock as Pair<Vec3i, AnionBlock>
            )

        }

        /** Assign Machine Core position */
        fun core(char: Char, block: AnionBlock): Builder {

            if (coreBlock != null) throw IllegalStateException("duplicate core registration in machine structure")
            charCustomRepresentations[char] = block

            // set relative pos to (0, 0, 0) and log the AnionBlock used as the core.
            coreBlock = Pair(Vec3i(0, 0, 0), block)
            return this

        }

        /** Assign AnionBlock */
        fun assign(char: Char, block: AnionBlock): Builder {

            charCustomRepresentations[char] = block
            return this

        }

        /** Assign Vanilla (Bukkit) Block */
        fun assign(char: Char, block: BlockType): Builder {

            charVanillaRepresentations[char] = block
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

                    var mappedBlock: BlockState? = null

	                // if mapped block exists, assign it
                    mappedBlock = charVanillaRepresentations[char]?.createBlockData()?.createBlockState()
                    if (mappedBlock != null) {
                        blockMap[Vec3i(localX, this.height, localY)] = mappedBlock
                        continue
                    }

                    // if mapped block did not exist, see if a vanilla representation exists
                    mappedBlock = charCustomRepresentations[char]?.getBlockState()
                    if (mappedBlock == null) throw IllegalStateException("block mapping missing for TODO FINISH THIS")

                    val coreBlockState = coreBlock?.second?.getBlockState()

                    // if duplicate coreBlock throw, else set coreBlock to true if blockState = coreBlock
                    if (hasCoreBlock && mappedBlock == coreBlockState)
                        throw IllegalStateException("duplicate core block assignment in structure")
                    else if (mappedBlock == coreBlockState)
                        hasCoreBlock = true

                    // TODO: height goes up per sliced layer.
                    //       we need to fetch the offset from the core block position in the schema:
                    //       for this to work we either need to cache the found width and length and
                    //       base our offset coordinates off of that, or find where the core is in relation
                    //       to the rest of the machine.
                    // now finally assign the blockmap
                    blockMap[Vec3i(localX, this.height, localY)] = mappedBlock

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
