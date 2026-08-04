package dev.diena.anion.features.starship

import dev.diena.anion.Tasks
import dev.diena.anion.data.database.AnionPersistence
import dev.diena.anion.extensions.adjacentBlocks
import dev.diena.anion.extensions.blockPos
import dev.diena.anion.extensions.div
import dev.diena.anion.extensions.minus
import dev.diena.anion.extensions.plus
import dev.diena.anion.extensions.vec3i
import dev.diena.anion.features.starship.simluated.StarshipSimulator
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.state.BlockState
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.craftbukkit.block.CraftBlock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** represents a collection of simulated blocks. logic and functionality split off into subclasses. */
// FIXME: Starship yaw can exist in states between what rotate the ship,
//        while starship position is clamped to integer values and is never changed if velocity is below 1 unit.
//        Update starship Position to support partial moves (so starships can still move at <=1 block a second)
class Starship {

    companion object {
        /** all loaded and active ships on the server */
        val loadedStarships: MutableMap<UUID, Starship> = ConcurrentHashMap()

        /** true while a starship is writing its blocks into the world.
         *
         *  [StarshipMovement] writes moved blocks with the UPDATE_NEIGHBORS flag, which synchronously fires
         *  BlockPhysicsEvent for every neighbour of every cell the ship lands on. those events are the ship's
         *  own movement echoing back, not player construction, and letting them reach [addBlock]/[updateBlock]
         *  makes ships steal each other's blocks: when two ships end up face-adjacent, each one sees a physics
         *  event on a cell next to its own blocks and claims it, after which both maps contain the same
         *  positions and every subsequent move has them stamping over each other.
         *
         *  main thread only — movement and block events both run there. */
        var applyingWorldChanges: Boolean = false
            private set

        /** the loaded ship in [level] that owns [pos], or null if no ship does. */
        fun starshipAt(level: ServerLevel, pos: Vec3i): Starship? =
            loadedStarships.values.firstOrNull { it.level == level && it.blockHashMap[pos] != null }

        /** runs [block] with [applyingWorldChanges] set, so block events it provokes are ignored by ships. */
        fun <T> writingToWorld(block: () -> T): T {

            val previous = applyingWorldChanges
            applyingWorldChanges = true

            try { return block() } finally { applyingWorldChanges = previous }

        }
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

        // register + persist the freshly built ship so callers cannot forget to (and desync the world).
        this.uuid = UUID.randomUUID()
        loadedStarships[this.uuid] = this
        AnionPersistence.saveStarship(this.uuid, this)

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
        this.blockHashMap = writingToWorld { StarshipMovement.move(vectorToMoveIn, this) }
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
        this.blockHashMap = writingToWorld { StarshipMovement.rotate(byAmount.toFloat(), this) }
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

        // TODO: move this out of removeBlock
        this.checkForSplit(block.vec3i)

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
        if (applyingWorldChanges) return false // a ship's own world writes must never grow another ship

        // blacklisted blocks go here
        if (block.type == Material.AIR) return false

        // a block can only ever belong to one ship. without this two face-adjacent ships both claim the same
        // cell and then stamp over each other on every move.
        val owner = starshipAt((block.world as CraftWorld).handle, block.vec3i)
        if (owner != null && owner !== this) return false

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

            this.simulator.addStarshipMass(block)

            return true

        }

        return false

    }

    /**
     * Refreshes the stored state of a block the ship *already owns*. Used for things like pistons.
     *
     * This deliberately does not add new blocks, unlike [addBlock]. It is driven by BlockPhysicsEvent,
     * which fires for cells the ship does not own — its own neighbours, terrain it just landed on, and the
     * hull of an adjacent ship. Adding on any of those is how a ship absorbs the world and its neighbours,
     * ending up with map entries whose world blocks belong to someone else. Genuine construction goes
     * through [addBlock]. Returns a boolean based on the result.
     */
    fun updateBlock(

        block: Block

    ) : Boolean {

        if (this.moving) return false
        if (applyingWorldChanges) return false // a ship's own world writes must never mutate any ship

        if ((block.world as CraftWorld).handle != this.level) return false
        if (this.blockHashMap[block.vec3i] == null) return false // not ours: do not claim it

        // blacklisted blocks go here. a block turning to air is a removal, which is removeBlock's job.
        if (block.type == Material.AIR) return false

        // position is unchanged, so the hitbox does not need rebuilding
        this.blockHashMap[block.vec3i] = (block as CraftBlock).blockState
        this.dirty = true

        return true

    }

    //////////////////
    ///// MISC HELPERS
    //////////////////

    /**
     * Checks asynchronously whether the most recent block removal split this ship into
     * disconnected sections, and if so spawns the smaller detached island as its own [Starship].
     *
     * @param removedPos position of the block that was just removed.
     */
    fun checkForSplit(removedPos: Vec3i) {

        // snapshot on the main thread: blockHashMap is mutated & reassigned by move/rotate/add/remove,
        // so the async job must never read the live map directly.
        //
        // the snapshot is taken in ship-local space (relative to origin) rather than world space. slowTick
        // translates the whole ship on the main thread while this job is in flight, so any world coordinate
        // captured here is stale by the time the result lands — and stale coordinates are not detectable after
        // the fact, because a ship shifted by one block still occupies most of the cells it used to. applying a
        // stale island would strip live blocks off this ship's map (leaving orphaned blocks in the world) and
        // hand the new ship a block set that overlaps this one, after which both ships write over each other.
        // local coordinates survive translation, so the result is rebased onto the current origin on apply.
        val originAtSnapshot = this.origin
        val yawAtSnapshot = this.yaw
        val snapshot = this.blockHashMap.keys.mapTo(HashSet()) { it - originAtSnapshot }
        val removedLocal = removedPos - originAtSnapshot

        Tasks.runAsync {

            val detached = StarshipSplit.detachedComponents(snapshot, removedLocal)
            if (detached.isEmpty()) return@runAsync

            Tasks.runSync {

                // rotation remaps local coordinates, so a rotated ship invalidates the snapshot outright.
                // dropping the result is safe: the next block removal re-runs the check against fresh geometry.
                if (this.yaw != yawAtSnapshot) return@runSync

                val rebased = detached.map { island -> island.mapTo(HashSet()) { it + this.origin } }

                // blocks may still have been added/removed while the floodfill ran; bail if the island no
                // longer matches what this ship actually owns.
                if (rebased.any { island -> island.any { this.blockHashMap[it] == null } }) return@runSync

                for (island in rebased) spawnDetachedShip(island)

            }

        }

    }

    /** strips [detached] off this ship and re-registers it as an independent [Starship]. main thread only. */
    private fun spawnDetachedShip(detached: Set<Vec3i>) {

        // strip the detached blocks off this ship
        for (pos in detached) this.blockHashMap.remove(pos)
        this.size = this.blockHashMap.size
        this.simulator.calculateTotalStarshipMass()
        this.hitbox.rebuildHitbox()
        this.dirty = true

        // build the new ship from the detached block set (world states re-read here on the main thread).
        // create() assigns the uuid, registers it in loadedStarships, and persists it.
        val blockPosSet = detached.mapTo(HashSet()) { BlockPos(it.x, it.y, it.z) }
        val createdStarship = Starship().create(blockPosSet, this.level.world)

        // inherit the parent's momentum by VALUE. create() already gave the child its own StarshipVelocity
        // (back-referencing the child); assigning this.velocity here instead would alias the parent's instance
        // and the child would drive the parent's movement, never its own — leaving the piece frozen in space.
        createdStarship.velocity.inheritFrom(this.velocity)

    }

}
