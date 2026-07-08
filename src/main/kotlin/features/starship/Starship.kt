package dev.diena.anion.features.starship

import dev.diena.anion.extensions.adjacentBlocks
import dev.diena.anion.extensions.div
import dev.diena.anion.extensions.plus
import dev.diena.anion.extensions.vec3i
import net.minecraft.core.Vec3i
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockType
import java.util.UUID

/**
 * main coordination class for Starship, StarshipMovement and Starship collision are modules that Starship uses.
 * */
class Starship {

    companion object {
        val loadedStarships: HashMap<UUID, Starship> = hashMapOf()
    }

    lateinit var world: World   // world that the ship currently exists in
    lateinit var origin: Vec3i  // approximated center of the starship, what is rotated around
    var yaw: Double = 0.0     // TODO: autodetect angle of starship based on detection time

    // what IS a starship?
    // i don't like it.... but it will work, i guess
    lateinit var blockHashMap: HashMap<Vec3i, BlockType> private set

    fun create(

        blockLocations: Set<Location>

    ): Starship {

        val worldCheck = blockLocations.first().world
        blockHashMap = hashMapOf() // init hashmap

        for (b in blockLocations) {

            if (b.world != worldCheck) throw IllegalStateException("Cannot create starship with blocks from different worlds!")

            val vec3i = Vec3i(b.blockX, b.blockY, b.blockZ)
            val blockType = b.block.type.asBlockType() ?: continue

            if (blockType == BlockType.AIR) continue

            this.world = worldCheck
            this.blockHashMap[vec3i] = blockType

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

        /*val addingVector = when (direction) {
            Direction.DOWN  -> Vec3i(0, toMove*-1, 0)
            Direction.UP    -> Vec3i(0, toMove*1, 0)
            Direction.EAST  -> Vec3i(toMove*1, 0, 0)
            Direction.WEST  -> Vec3i(toMove*-1, 0, 0)
            Direction.NORTH -> Vec3i(0, 0, toMove*-1)
            Direction.SOUTH -> Vec3i(0, 0, toMove*1)
        }*/

        val newBlockMap: HashMap<Vec3i, BlockType> = hashMapOf()

        // this takes our old hash map and shifts values in the new block map
        for (vec in blockHashMap.keys) {
            newBlockMap[vec+vectorToMoveIn] = blockHashMap[vec] ?: continue
        }

        // move ship
        for ((vec, b) in newBlockMap) {
            world.setBlockData(
                vec.x, vec.y, vec.z, b.createBlockData()
            )

            blockHashMap.remove(vec)
        }

        // REmove old ship section
        for ((vec, _) in blockHashMap) {
            world.setBlockData(
                vec.x, vec.y, vec.z, BlockType.AIR.createBlockData()
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

        if (block.world != this.world) throw IllegalStateException("you cannot add a block to a ship from another world")

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

        if (block.world != this.world) throw IllegalStateException("you cannot remove a block from a ship from another world")
        // this might be an obvious optimization, but i'm proud of it :3
        for (b in block.adjacentBlocks) {
            // if not in entry continue, if in no entries do not add block.
            if (blockHashMap[b.vec3i] != null) continue

            // if at least one adjacent block was found, add it to the ship
            blockHashMap[block.vec3i] = block.type.asBlockType() ?: return
        }

    }

}
