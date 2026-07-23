package dev.diena.anion.features.starship

import dev.diena.anion.data.database.AnionPersistence
import dev.diena.anion.extensions.adjacentBlocks
import dev.diena.anion.extensions.blockPos
import dev.diena.anion.extensions.div
import dev.diena.anion.extensions.minus
import dev.diena.anion.extensions.plus
import dev.diena.anion.extensions.rotateRight
import dev.diena.anion.extensions.vec3i
import dev.diena.anion.features.starship.simluated.StarshipSimulator
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.PositionMoveRotation
import net.minecraft.world.entity.Relative
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.craftbukkit.block.CraftBlock
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Player
import java.util.UUID

/** represents a collection of simulated blocks. logic and functionality split off into subclasses. */
// FIXME: Starship yaw can exist in states between what rotate the ship, while starship position is clamped to integer values and is never changed if velocity is below 1 unit.
class Starship {

    companion object {
        /** all loaded and active ships on the server */
        val loadedStarships: HashMap<UUID, Starship> = hashMapOf()
    }

    lateinit var uuid: UUID
    lateinit var level: ServerLevel           // nms Level that the ship currently exists in
    lateinit var origin: Vec3i                // approximated center of the starship, what is rotated around
    lateinit var hitbox: StarshipHitbox       // ship hitbox
    lateinit var simulator: StarshipSimulator // starship world interaction

    lateinit var velocity: StarshipVelocity
    var yaw: Double = 0.0
    var size: Int = 0

    var dirty: Boolean = false      // marks if we need to save starship in database
    private var moving = false      // internal check to prevent concurrent modification

    /** Represents the blocks that make up a ship. Readable publicly, writable privately. */
    lateinit var blockHashMap: HashMap<Vec3i, BlockState> private set // nms BlockState

    //////////////////////////////////////
    ///// TICK OPERATIONS (SlowTick, Tick)
    //////////////////////////////////////

    fun tick() {

        // NO-OP atm

    }

    fun slowTick() {

        // every move tick we need to apply a simulator.... which would be every tick
        // applying velocity not only mutates our velocity value, it moves the starship too.
        // this is *fine*, but not ideal, since movement should be done after our simulator layer is complete.

        // simulate starship (for now apply static gravity if not in world ending in _space.)
        simulator.simulate()

        // applyVelocity in StarshipVelocity calls our Starship.move() class, so we can run Simulator before it and update the velocity values
        velocity.applyVelocity()

    }

    /////////////////////////////////////////////////
    ///// DATA OPERATIONS (Creation, Loading, Saving)
    /////////////////////////////////////////////////

    /** Creates a fresh instance of a [Starship] with the provided Block locations. */
    fun create(

        blockPosSet: Set<BlockPos>,
        setWorld: World

    ): Starship {

        this.level = (setWorld as CraftWorld).handle           // init level
        this.blockHashMap = hashMapOf()                        // init hashmap
        this.simulator = StarshipSimulator.new(this) // init simulator

        for (b in blockPosSet) {

            val block = level.getBlockState(b.blockPos)
            this.blockHashMap[b] = block

        }

        // calculate center of starship
        // FIXME: RECALCULATE EVERY TIME A BLOCK IS ADDED
        var vectorAddedTo = Vec3i(0, 0, 0)
        for (v in this.blockHashMap.keys) {

            vectorAddedTo += v

        }

        this.origin = vectorAddedTo/blockHashMap.size
        this.hitbox = StarshipHitbox.new(this)
        this.velocity = StarshipVelocity.new(this)
        this.yaw = 1.0
        this.size = blockPosSet.size

        this.simulator.calculateTotalStarshipMass() // calculate initial starship mass

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
        this.hitbox = StarshipHitbox.new(this)
        this.velocity = StarshipVelocity.new(this) // FIXME: SAVE VELOCITY ON SHIP UNLOAD
        this.simulator = StarshipSimulator.new(this) // FIXME: Save Simulator on unload.

        return this

    }

    ////////////////////////////////////////////
    ///// MOVEMENT OPERATIONS (Moving, Rotating)
    ////////////////////////////////////////////

    /** moves the ship by the given [Vec3i] */
    fun move(

        vectorToMoveIn: Vec3i,

    ) : Boolean {

        this.moving = true

        val (canMove, _) = StarshipCollision.processMoveCollision(vectorToMoveIn, this)
        if (!canMove) {

            this.moving = false
            return false

        }

	    this.origin += vectorToMoveIn                  // translate origin
        this.blockHashMap = StarshipMovement.move(vectorToMoveIn, this)
        this.hitbox.moveHitbox(vectorToMoveIn) // translate hitbox
        this.dirty = true
        this.moving = false

        return true

    }

    /** increments yaw by given amount, rotates if */
    fun rotate(

        byAmount: Double // can be negative

    ) : Boolean {

        this.moving = true

        if (!StarshipCollision.processRotationCollision(byAmount.toFloat(), this)) {

            this.moving = false
            return false

        }

        // origin is unchanged
        this.blockHashMap = StarshipMovement.rotate(byAmount.toFloat(), this)
        this.hitbox.rebuildHitbox() // recompute hitbox
        this.dirty = true
        this.moving = false

        return true

    }

    fun changeWorld(

        newWorld: World,
        posInNewWorld: Vec3i

    ) : Boolean {

        TODO("NYI")

    }

    /** effectively teleports the starship to the given coordinates by manipulating the move method. */
    fun teleportInWorld(

        posInWorldToMoveTo: Vec3i,
        preserveVelocity: Boolean, // TODO: implement with velocity

    ): Boolean {

        if (!preserveVelocity) velocity.resetVelocity()
        return this.move(posInWorldToMoveTo-this.origin)

    }

    ///////////////////////////////////////////////////
    ///// BLOCK OPERATIONS (Adding, Removing, Updating)
    ///////////////////////////////////////////////////

    /** removes block from starship. returns a boolean based on the result. */
    fun removeBlock(

        block: Block

    ) : Boolean {

        if (this.blockHashMap[block.vec3i] == null) return false // fail if removing block not on starship
        if (this.moving) return false                            // fail if we're moving
        if ((block.world as CraftWorld).handle != this.level) {  // fail if removing block that is in another level (impossible?)
            throw IllegalStateException("you cannot remove a block from a ship from another level!")
        }

        this.blockHashMap.remove(block.vec3i)

        // deregister and remove ship if all blocks gone
        if (blockHashMap.isEmpty()) {

            loadedStarships.remove(this.uuid)
            AnionPersistence.deleteStarship(this.uuid)

            return true

        }

        this.simulator.removeStarshipMass(block)

        this.hitbox.rebuildHitbox()
        this.dirty = true
        return true

    }

    /** adds block to starship if block is adjacent to the ship. returns a boolean based on the result. */
    // FIXME: this currently adds ANY adjacently placed block to the ship ONTO the ship.
    //        in this current state, blocks cannot be placed on the world directly adjacent
    //        to the ship without being added onto it. a more ideal solution would be to ONLY
    //        add a block of the clicked block (in placement) was already part of a starship
    fun addBlock(

        block: Block

    ) : Boolean {

        if (this.moving) return false

        // only check blocks adjacent to the placed block
        for (b in block.adjacentBlocks) {

            if (this.blockHashMap[b.vec3i] == null) continue        // if not in entry continue, if in no entries do not add block.
            if ((block.world as CraftWorld).handle != this.level) { // fail if adding block that is in another level (impossible?)
                throw IllegalStateException("you cannot add a block to a ship from another level!")
            }

            // if at least one adjacent block was found, add it to the ship and break the loop
            this.blockHashMap[block.vec3i] = (block as CraftBlock).blockState
            this.hitbox.rebuildHitbox()
            this.dirty = true

            this.simulator.removeStarshipMass(block)

            return true

        }

        return false

    }

    // TODO: combine/refactor updateBlock with addBlock as the functions are literally identical
    /** used for things like pistons. returns a boolean based on the result. */
    fun updateBlock(

        block: Block

    ) : Boolean {

        if (this.moving) return false

        for (b in block.adjacentBlocks) {

            // if not in entry continue, if in no entries do not add block.
            if (blockHashMap[b.vec3i] == null) continue

            // if at least one adjacent block was found, add it to the ship
            this.blockHashMap[block.vec3i] = (block as CraftBlock).blockState
            this.hitbox.rebuildHitbox()
            this.dirty = true

            return true

        }

        return false

    }

}
