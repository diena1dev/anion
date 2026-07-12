package dev.diena.anion.features.starship

import dev.diena.anion.data.database.AnionPersistence
import dev.diena.anion.extensions.adjacentBlocks
import dev.diena.anion.extensions.blockPos
import dev.diena.anion.extensions.div
import dev.diena.anion.extensions.plus
import dev.diena.anion.extensions.rotateRight
import dev.diena.anion.extensions.vec3i
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.PositionMoveRotation
import net.minecraft.world.entity.Relative
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockType
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.craftbukkit.block.CraftBlock
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Player
import java.util.UUID

/**
 * main coordination class for Starship, StarshipMovement and Starship collision are modules that Starship uses.
 * */
class Starship {

    companion object {
        /** all loaded and active ships in the world */
        val loadedStarships: HashMap<UUID, Starship> = hashMapOf()
    }

    lateinit var uuid: UUID
    lateinit var level: ServerLevel // nms Level that the ship currently exists in
    lateinit var origin: Vec3i      // approximated center of the starship, what is rotated around
    lateinit var shipAABB: AABB     // ship hitbox
    var yaw: Double = 0.0           // TODO: autodetect angle of starship based on detection time
    var dirty: Boolean = false      // marks if we need to save starship in database
    private var moving = false      // internal check to prevent concurrent modification

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
        rebuildAABB()

        return this

    }

    /** restores a starship from data. */
    internal fun load(

        uuid: UUID,
        level: ServerLevel,
        origin: Vec3i,
        yaw: Double,
        blocks: HashMap<Vec3i, BlockState>

    ): Starship {

        this.uuid = uuid
        this.level = level
        this.origin = origin
        this.yaw = yaw
        this.blockHashMap = blocks

        rebuildAABB()

        return this

    }

    // FIXME: just split this function up, it is waaaaaaaaaaay too long
    fun move(

        vectorToMoveIn: Vec3i,  // Vec3i to move the ship by, relative to current position

    ) {

        // lock updates
        moving = true

        val newBlockMap: HashMap<Vec3i, BlockState> = hashMapOf()            // nms BlockState collection
        val beMap: HashMap<Vec3i, CompoundTag>      = hashMapOf()            // serialized nms BlockEntities
        val provider                                = level.registryAccess() // nbt fuckery

        // check collision, if true then safe otherwise fail
        if (!StarshipCollision.processCollision(this, vectorToMoveIn)) {
            moving = false // unlock if fail
            return
        }

        val riders = collectRiders()

        for ((vec, _) in blockHashMap) {
            // shift blocks
            newBlockMap[vec + vectorToMoveIn] = blockHashMap[vec] ?: continue

            // store BEs and remove BE blocks
            val be = level.getBlockEntity(vec.blockPos) ?: continue
            val nbt = be.saveWithFullMetadata(provider)
            val newPos = vec + vectorToMoveIn
            nbt.putInt("x", newPos.x)
            nbt.putInt("y", newPos.y)
            nbt.putInt("z", newPos.z)
            beMap[newPos] = nbt
            level.removeBlockEntity(vec.blockPos)

        }

        // REmove old ship section
        for ((vec, _) in blockHashMap) {
            // 4. no observer updates, 16. no shape recalc, 32. no item drops
            level.setBlock(vec.blockPos, Blocks.AIR.defaultBlockState(), 4 or 16 or 32)
        }

        // actually MOVE ship blocks
        for ((vec, b) in newBlockMap) {
            //blockHashMap.remove(vec)
            // 1. update neighboring blocks, 4. no observer updates, 16. no shape recalc
            level.setBlock(vec.blockPos, b, 1 or 4 or 16)
        }

        // transfer scheduled block/fluid ticks to new positions
        //StarshipTicks.transferT   icks(level, blockHashMap.keys, vectorToMoveIn)

        // then load ship BEs
        for ((vec, nbt) in beMap) {
            val bs = newBlockMap[vec] ?: continue
            val newBe = BlockEntity.loadStatic(vec.blockPos, bs, nbt, provider) ?: continue
            level.setBlockEntity(newBe)
        }

        // finally send update packets to the client
        StarshipPackets.sendSections(level, newBlockMap.keys, blockHashMap.keys, newBlockMap)

        blockHashMap = newBlockMap
        origin += vectorToMoveIn
        shipAABB = shipAABB.move(vectorToMoveIn.x.toDouble(), vectorToMoveIn.y.toDouble(), vectorToMoveIn.z.toDouble())
        dirty = true

        // and teleport the player after all that fun is done
        val newX = vectorToMoveIn.x.toDouble()
        val newY = vectorToMoveIn.y.toDouble()
        val newZ = vectorToMoveIn.z.toDouble()

        for (entity in riders) {

            val bukkitEntity = entity.bukkitEntity
            if (bukkitEntity is Player) {
                // I FUCKING HATE NMS
                val destination = PositionMoveRotation(Vec3(newX, newY, newZ), Vec3.ZERO, 0f, 0f)

                (bukkitEntity as CraftPlayer).handle.connection.teleport(
                    destination,
                    setOf(Relative.X, Relative.Y, Relative.Z, Relative.Y_ROT, Relative.X_ROT)
                )

            } else {

                bukkitEntity.teleport(bukkitEntity.location.add(newX, newY, newZ))

            }

        }

        // unlock updates
        moving = false

    }

    // rebuild the internal hitbox and save us like nanoseconds
    private fun rebuildAABB() {

        // this is safe, right? :p
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE; var maxZ = Int.MIN_VALUE

        for (v in blockHashMap.keys) {
            if (v.x < minX) minX = v.x; if (v.x > maxX) maxX = v.x
            if (v.y < minY) minY = v.y; if (v.y > maxY) maxY = v.y
            if (v.z < minZ) minZ = v.z; if (v.z > maxZ) maxZ = v.z

        }
        shipAABB = AABB(
            (minX - 1).toDouble(), (minY - 1).toDouble(), (minZ - 1).toDouble(),
            (maxX + 2).toDouble(), (maxY + 2).toDouble(), (maxZ + 2).toDouble()
        )

    }

    // check each entity inside the AABB against the blockMap
    private fun collectRiders(): List<Entity> {

        // grab vanilla entities from the AABB functions
        val foundEntities = level.getEntities(null as Entity?, shipAABB) { entity ->
            val bp = entity.blockPosition()

            // fucking indentations, linus is rolling over in his grave
            for (x in -1..1) {
                for (y in -1..1) {
                    for (z in -1..1) {
                        if (blockHashMap.containsKey(Vec3i(bp.x + x, bp.y + y, bp.z + z))) return@getEntities true
                    }
                }
            }

            // if we don't find the entity nearby the ship we just leave it behind :3
            // (do NOT jump off of your ship you will be left behind in the cold, empty void)
            false
        }

        return foundEntities

    }

    // 90° Counterclockwise: Swap the x and y values, and multiply the new x by -1.
    // Example: \(\left[\begin{matrix}4\\ 2\end{matrix}\right]\) becomes \(\left[\begin{matrix}-2\\ 4\end{matrix}\right]\)90°
    // Clockwise: Swap the x and y values, and multiply the new y by -1.
    // Example: \(\left[\begin{matrix}4\\ 2\end{matrix}\right]\) becomes \(\left[\begin{matrix}2\\ -4\end{matrix}\right]\)
    /** can only rotate yaw, not pitch or roll for obvious reasons (the ship would be torn apart....) */
    /*fun rotate(

        byAmount: Double // can be negative

    ) {

        // helper function
        fun Double.angleRange(): BlockFace = when (toDouble()) {
            in 0.0..90.0    -> return BlockFace.SOUTH
            in 90.0..180.0  -> return BlockFace.EAST
            in 180.0..270.0 -> return BlockFace.NORTH
            in 270.0..360.0 -> return BlockFace.WEST
            else -> { throw IllegalStateException("what the fuck did you do") }
        }

        // snapshot our old yaw so we know how much to rotate by based on the current rotation of the ship
        val angleSnapshot = yaw

        // definitely didn't overcomplicate this before using modulo
        // add amount to yaw first
        yaw = ((yaw+byAmount % 360) + 360) % 360

        // no change
        if (angleSnapshot.angleRange() == yaw.angleRange()) return

        // change
        for (i in 1..4) {

            if (angleSnapshot.angleRange().rotateRight() != angleSnapshot.angleRange()) continue else break

        }

        // get facing component for directional blocks and rotate them accordingly
        BlockType.WAXED_WEATHERED_CUT_COPPER_STAIRS.createBlockData().facing





        // new angle
        val vecToRotateWith = angleRange(yaw)

    }*/

    fun changeWorld(

        newWorld: World,
        posInNewWorld: Vec3i

    ) {

    }

    // TODO: rethink parameters for block functions

    fun removeBlock(

        block: Block

    ) {

        if (moving) return

        if ((block.world as CraftWorld).handle != level) throw IllegalStateException("you cannot remove a block from a ship from another level!")

        blockHashMap.remove(block.vec3i)

        // deregister and remove ship if all blocks gone
        if (blockHashMap.isEmpty()) {
            loadedStarships.remove(this.uuid)
            AnionPersistence.deleteStarship(this.uuid)
            return
        }

        rebuildAABB()
        dirty = true

    }

    /** adds block to starship if block is adjacent to the ship */
    // FIXME: this currently adds ANY adjacently placed block to the ship ONTO the ship.
    //        in this current state, blocks cannot be placed on the world directly adjacent
    //        to the ship without being added onto it. a more ideal solution would be to ONLY
    //        add a block of the clicked block (in placement) was already part of a starship
    fun addBlock(

        block: Block

    ) {

        if (moving) return

        if ((block.world as CraftWorld).handle != level) throw IllegalStateException("you cannot add a block to a ship from another world")
        // this might be an obvious optimization, but i'm proud of it :3
        for (b in block.adjacentBlocks) {
            // if not in entry continue, if in no entries do not add block.
            if (blockHashMap[b.vec3i] == null) continue

            // if at least one adjacent block was found, add it to the ship
            blockHashMap[block.vec3i] = (block as CraftBlock).blockState
            rebuildAABB()
            dirty = true
        }

    }

    fun updateBlock(

        block: Block

    ) {

        if (moving) return
        for (b in block.adjacentBlocks) {
            // if not in entry continue, if in no entries do not add block.
            if (blockHashMap[b.vec3i] == null) continue

            // if at least one adjacent block was found, add it to the ship
            blockHashMap[block.vec3i] = (block as CraftBlock).blockState
            dirty = true
        }

    }

}
