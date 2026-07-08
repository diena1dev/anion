package dev.diena.anion.features.starship

import dev.diena.anion.extensions.adjacentBlocks
import dev.diena.anion.extensions.blockPos
import dev.diena.anion.extensions.div
import dev.diena.anion.extensions.plus
import dev.diena.anion.extensions.vec3i
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.state.BlockState
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockType
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.craftbukkit.block.CraftBlock
import java.util.UUID

/**
 * main coordination class for Starship, StarshipMovement and Starship collision are modules that Starship uses.
 * */
class Starship {

    companion object {
        val loadedStarships: HashMap<UUID, Starship> = hashMapOf()
    }

    lateinit var level: ServerLevel // nms Level that the ship currently exists in
    lateinit var origin: Vec3i      // approximated center of the starship, what is rotated around
    var yaw: Double = 0.0           // TODO: autodetect angle of starship based on detection time

    /** Represents the blocks that make up a ship. Readable publicly, writable privately. */
    lateinit var blockHashMap: HashMap<Vec3i, BlockState> private set // nms BlockState

    // internal airBLock reference
    private val airBlock = net.minecraft.world.level.block.Block.byItem(Items.AIR)

    /** Creates a fresh instance of a [Starship] with the provided Block locations. */
    fun create(

        blockPosSet: Set<BlockPos>,
        setWorld: World

    ): Starship {

        level = (setWorld as CraftWorld).handle // init level
        blockHashMap = hashMapOf()              // init hashmap

        for (b in blockPosSet) {

            val block = level.getBlockState(b.blockPos)

            // don't add air
            if (block.`is`(airBlock)) continue

            this.blockHashMap[b] = block

        }

        // calculate center of starship
        var vectorAddedTo = Vec3i(0, 0, 0)
        for (v in blockHashMap.keys) {
            vectorAddedTo += v
        }

        this.origin = vectorAddedTo/blockHashMap.size

        return this

    }

    fun move(

        //direction: Direction, // direction to move in (may change) // it changed
        //toMove: Int           // amount to move in blocks
        vectorToMoveIn: Vec3i,  // Vec3i to move the ship by, relative to current position

    ) {

        /**
         * level.setBlock(blockPos, blockState, flags)
         * // flags constants on Block:
         * //   UPDATE_NEIGHBORS = 1
         * //   UPDATE_CLIENTS   = 2
         * //   UPDATE_INVISIBLE = 4   (no packet, no physics)
         * //   UPDATE_KNOWN_SHAPE = 16
         *
         * level.removeBlock(blockPos, /*move=*/false)
         * level.destroyBlock(blockPos, /*drop=*/true)
         * level.destroyBlock(blockPos, /*drop=*/true, entity, /*recursion=*/0)
         *
         * // Bypassing neighbor updates (silent placement):
         * level.setBlock(blockPos, state, 2 or 16)  // client update + known shape
         */

        val newBlockMap: HashMap<Vec3i, BlockState> = hashMapOf() // nms BlockState

        // BEGIN COLLISION INSERT

        // if block moving to not in already existing entry (e.g. notnull key for blockHashMap)
        // then continue
        // if block moving to not in entry, fetch if air.
        // if true, set newBlockMap and continue
        // if false, cancel movement // FIXME: do not cancel, move as close as possible

        // this takes our old hash map and shifts values in the new block map
        for (vec in blockHashMap.keys) {

            val vecToMoveTo = vec+vectorToMoveIn

            if (blockHashMap[vecToMoveTo] != null) {

                newBlockMap[vecToMoveTo] = blockHashMap[vec] ?: continue

            } else if (level.getBlockState(vecToMoveTo.blockPos).isAir) {

                newBlockMap[vecToMoveTo] = blockHashMap[vec] ?: continue

            } else {
                return
            }

        }

        // END INSERT



        // move ship
        for ((vec, b) in newBlockMap) {

            // TODO: `4 or 16` (no client sending) instead of `3 or 32` (no drops, no physics when replacing),
            //        in order to reduce client packet spam and improve performance of starships.
            level.setBlock(vec.blockPos, b, 2 or 32)

            blockHashMap.remove(vec)
        }

        // REmove old ship section
        for ((vec, _) in blockHashMap) {
            level.setBlock(
                vec.blockPos, airBlock.defaultBlockState(), 2 or 32
            )
        }

        blockHashMap = newBlockMap
        origin+=vectorToMoveIn

    }

    // 90° Counterclockwise: Swap the x and y values, and multiply the new x by -1.
    // Example: \(\left[\begin{matrix}4\\ 2\end{matrix}\right]\) becomes \(\left[\begin{matrix}-2\\ 4\end{matrix}\right]\)90°
    // Clockwise: Swap the x and y values, and multiply the new y by -1.
    // Example: \(\left[\begin{matrix}4\\ 2\end{matrix}\right]\) becomes \(\left[\begin{matrix}2\\ -4\end{matrix}\right]\)
    /** can only rotate yaw, not pitch or roll for obvious reasons (the ship would be torn apart....) */
    fun rotate(

        byAmount: Double // can be negative

    ) {

        // helper function
        fun angleRange(a: Double) = when (a) {
            in 0.0..90.0    -> {}
            in 90.0..180.0  -> {}
            in 180.0..270.0 -> {}
            in 270.0..360.0 -> {}
            else -> { throw IllegalStateException("what the fuck did you do") }
        }

        // get facing component for directional blocks and rotate them accordingly
        BlockType.WAXED_WEATHERED_CUT_COPPER_STAIRS.createBlockData().facing

        val angleSnapshot = yaw

        // definitely didn't overcomplicate this before using modulo
        yaw = ((yaw+byAmount % 360) + 360) % 360

        // new angle
        val vecToRotateWith = angleRange(yaw)

    }

    fun changeWorld(

        newWorld: World,
        posInNewWorld: Vec3i

    ) {

    }

    // TODO: rethink parameters for block functions

    fun removeBlock(

        block: Block

    ) {

        if ((block.world as CraftWorld).handle != level) throw IllegalStateException("you cannot add a block to a ship from another level!")

        blockHashMap.remove(block.vec3i)

    }

    /** adds block to starship if block is adjacent to the ship */
    // FIXME: this currently adds ANY adjacently placed block to the ship ONTO the ship.
    //        in this current state, blocks cannot be placed on the world directly adjacent
    //        to the ship without being added onto it. a more ideal solution would be to ONLY
    //        add a block of the clicked block (in placement) was already part of a starship
    fun addBlock(

        block: Block

    ) {

        if ((block.world as CraftWorld).handle != level) throw IllegalStateException("you cannot remove a block from a ship from another world")
        // this might be an obvious optimization, but i'm proud of it :3
        for (b in block.adjacentBlocks) {
            // if not in entry continue, if in no entries do not add block.
            if (blockHashMap[b.vec3i] != null) continue

            // if at least one adjacent block was found, add it to the ship
            blockHashMap[block.vec3i] = (block as CraftBlock).blockState
        }

    }

}
