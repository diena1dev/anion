package dev.diena.anion.features.starship

import dev.diena.anion.data.database.AnionPersistence
import dev.diena.anion.extensions.adjacentBlocks
import dev.diena.anion.extensions.blockPos
import dev.diena.anion.extensions.div
import dev.diena.anion.extensions.minus
import dev.diena.anion.extensions.plus
import dev.diena.anion.extensions.rotateRight
import dev.diena.anion.extensions.rotationOf
import dev.diena.anion.extensions.stepsFromTo
import dev.diena.anion.extensions.toFace
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
import java.util.concurrent.ConcurrentHashMap

/** represents a collection of simulated blocks. logic and functionality split off into subclasses. */
// FIXME: Starship yaw can exist in states between what rotate the ship, while starship position is clamped to integer values and is never changed if velocity is below 1 unit.
class Starship {

    companion object {
        /** all loaded and active ships on the server */
        val loadedStarships: MutableMap<UUID, Starship> = ConcurrentHashMap()

        /** the loaded ship occupying [vec] in [level], if any. used to claim freshly assembled machines. */
        fun starshipAt(level: ServerLevel, vec: Vec3i): Starship? =
            loadedStarships.values.firstOrNull { it.level == level && it.blockHashMap.containsKey(vec) }
    }

    lateinit var uuid: UUID
    lateinit var level: ServerLevel           // nms Level that the ship currently exists in
    lateinit var origin: Vec3i                // approximated center of the starship, what is rotated around
    lateinit var hitbox: StarshipHitbox       // ship hitbox
    lateinit var simulator: StarshipSimulator // starship world interaction
    lateinit var machines: StarshipMachines   // machines carried by this ship

    lateinit var velocity: StarshipVelocity
    var yaw: Double = 0.0
    var size: Int = 0

    var dirty: Boolean = false      // marks if we need to save starship in database

    // moves run on the main thread, machines tick on the async pool — this is read from there, so it
    // has to be volatile or a machine can sit on a stale copy indefinitely.
    /** true while a move/rotate is rewriting the world. carried machines skip their ticks. */
    @Volatile var moving = false; private set

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
        this.machines = StarshipMachines.new(this)
        this.yaw = 1.0
        this.size = blockPosSet.size

        this.machines.rebuild() // claim any machine already assembled inside the detected blocks

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
        this.machines = StarshipMachines.new(this)

        // machines load on their own chunk, which may come up before or after this ship — rebuild()
        // catches the ones already in memory, and any that load later claim this ship themselves.
        this.machines.rebuild()

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
        this.machines.translate(vectorToMoveIn) // carry machines along, after the world is rewritten
        this.hitbox.moveHitbox(vectorToMoveIn) // translate hitbox
        this.dirty = true
        this.moving = false

        this.machines.revalidate() // machines are only unfrozen now, so re-check against the settled world

        return true

    }

    /** increments yaw by given amount, rotates if */
    fun rotate(

        byAmount: Double // can be negative

    ) : Boolean {

        this.moving = true

        // narrowed once and reused: the collision check has to resolve the exact same face the yaw
        // update below does, and Float vs Double rounding can disagree right on a face boundary.
        val byAngle = byAmount.toFloat()

        if (!StarshipCollision.processRotationCollision(byAngle, this)) {

            this.moving = false
            return false

        }

        // yaw lives here, not in StarshipMovement — that class only rewrites blocks, and the machine
        // transform has to be driven off the exact same step count the blocks were rotated by.
        val oldYaw = this.yaw
        this.yaw = ((oldYaw + byAngle % 360) + 360) % 360 // modulo to wraparound whatever angle we get
        val steps = stepsFromTo(oldYaw.toFace(), this.yaw.toFace())

        // sub-face yaw change: nothing in the world moves, but the new yaw still has to be persisted
        if (steps == 0) {

            this.dirty = true
            this.moving = false
            return true

        }

        // origin is unchanged
        this.blockHashMap = StarshipMovement.rotate(steps, this)
        this.machines.rotate(rotationOf(steps)) // same steps as the blocks, so machines land with them
        this.hitbox.rebuildHitbox() // recompute hitbox
        this.dirty = true
        this.moving = false

        this.machines.revalidate()

        return true

    }

    // TODO: carried machines need their level reassigned here, not just their origin — relocate() only
    //       moves them within a level. see StarshipMachines.
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

        // a machine that just lost its core block has no anchor left to be found by — tear it down.
        // machines losing a non-core block need no handling here, isIntact() catches those.
        this.machines.coreAt(block.vec3i)?.disassemble()

        // deregister and remove ship if all blocks gone
        if (blockHashMap.isEmpty()) {

            this.machines.detachAll()
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
