package dev.diena.anion.features.machine

import dev.diena.anion.Anion
import dev.diena.anion.Tasks
import dev.diena.anion.data.database.AnionPersistence
import dev.diena.anion.extensions.plus
import dev.diena.anion.extensions.rotate
import org.bukkit.craftbukkit.block.data.CraftBlockData
import dev.diena.anion.features.custom.AnionResource
import dev.diena.anion.features.starship.Starship
import net.minecraft.core.Vec3i
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Rotation
import org.bukkit.NamespacedKey
import org.bukkit.block.BlockState
import org.bukkit.block.data.BlockData
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/// Machine member inventory — every variable and function of the base class,
/// grouped by who supplies it and who is allowed to touch it. see MACHINE_README.md
//|
//| [CONSTRUCTOR — type config. val, immutable. one set of values per registered machine type]
//|- displayName: String
//|- blockSet: BlockSet                                  // structure definition, compared at origin+rotation
//|- namespacedKey: NamespacedKey = adaptedFromDisplayName // AnionResource impl, used as machine-type registry key
//|- dataChannels: Map<NamespacedKey, DataChannel<*>>?   // must specify opt-out with null, all get/set methods have null handlers
//|
//| [INSTANCE STATE — var, owned by base. written ONLY by assemble()/load()/pipeline, subclasses read]
//|- uuid: UUID                  lateinit                // identity in activeMachines + database
//|- level: ServerLevel          lateinit
//|- origin: Vec3i               lateinit                // core block world pos, rotation pivot
//|- rotation: Rotation          = NONE                  // solved during assembly wrench-check
//|- intact: Boolean             = false; protected set  // cached slowTick structure result, gates tick()
//|- dirty: Boolean              = false                 // persistence flag, cleared on save
//|- starship: Starship?         = null; internal set    // carrier ship, null if grounded. owned by StarshipMachines
//|
//| [HELPERS — val, owned by base, initialized by assemble()/load() via Helper.new(this)]
//|- display: MachineDisplay     lateinit  // .line(key, Component) / .clearLine(key), flushed sync each slowTick
//|- data: MachineData           lateinit  // .get(channel) / .set(channel, value) / .link(machine) / .unlink(machine)
// TODO: data is going to work differently than this, data aaaaaaaaaaaaaa
//       is going to send out a signal along a line, whatever grabs that signal can use it
//       for an example a mainframe can use the signal that gets emitted and reroute it based
//       on the configuration stored for the signed incoming signal.
//|
//| [FINAL PIPELINE — fun, base implements, nobody overrides]
//|- assemble(level: ServerLevel, origin: Vec3i, rotation: Rotation): Machine  // register, init helpers, persist, onAssemble()
//|- load(uuid: UUID, level: ServerLevel, origin: Vec3i, rotation: Rotation, tag: CompoundTag): Machine
//|                              // db restore: bind, loadState(tag), register, onAssemble()
//|- disassemble()               // onDisassemble(), display.shutdown(), data.unlinkAll(), deregister, delete save
//|- relocate(newOrigin: Vec3i, addedRotation: Rotation)  // ship-driven move/rotate, then onRelocate()
//|- revalidate()                // forced structure re-check, called once a carrier move settles
//|- runTick()                   // ticker entry: if (intact) tick()
//|- runSlowTick()               // ticker entry: intact = isIntact(); if (intact) slowTick(); display.flush()
//|
//| [PROTECTED UTILS — fun, subclasses call, never override]
//|- localToWorld(offset: Vec3i): Vec3i                  // origin-relative, rotation-applied
//|- blockAt(offset: Vec3i): BlockState                  // world read at local offset
//|- setBlockAt(offset: Vec3i, state: BlockState)        // batched, committed sync
//|- markDirty()
//|
//| [HOOKS — subclasses implement/override]
//|- tick()             abstract  ASYNC! every game tick, only runs while intact
//|- slowTick()         abstract  ASYNC! every second, tickrate-agnostic, wary of desyncs
//|- isIntact(): Boolean open     default = blockSet vs world at origin+rotation.
//|                               override for dynamic structures (door checks its own extension state,
//|                               tank returns broken-wall vectors)
//|- onAssemble()       open      extra init not covered by pipeline (buffers/ports are automatic)
//|- onDisassemble()    open      extra teardown: flush recipes, destroy buffers, shutdown attached entities
//|- onRelocate()       open      fix up cached absolute positions after a ship moved this machine
//|- saveState(tag)     open      write mutable state that cannot be re-derived (progress, extension, ...)
//\- loadState(tag)     open      read it back. runs after bind(), before registration
//
// subclass adds ONLY: (1) family config as constructor vals (doorWidth, doorMaterial, ...)
//                     (2) mutable behavior state (extension: Int, progress: Int, ...)
// never add: cached world blocks (world is source of truth), values derivable from blockSet,
//            or anything a helper owns (display lines, channel values, links)


// open is can override
// abstract is must override
// fun is a static callback that cannot be changed
// open functions can have super calls that still use the original logic + whatever other things you add
/** IMPORTANT: **Do not** access any lateinit vars from outside of functions. */
abstract class Machine(

    displayName: String,    // self-explanatory
    val blockSet: BlockSet?,    // fine to have in 90% of machines, blockSet doesn't have to be used by anything since the machine handles intact logic
    //dataChannels: Map<NamespacedKey, DataChannel<*>>?, // TODO, datachannel

    override val namespacedKey: NamespacedKey = NamespacedKey(Anion.NAMESPACE, displayName.lowercase().replace(' ', '_')),

) : AnionResource {

    /** List of all loaded machines, tick list */
    // TODO: move to per-chunk/load-balancing ticking so all machines don't try to tick at once
    companion object {
        val activeMachines: ConcurrentHashMap<UUID, Machine> = ConcurrentHashMap()
    }

    lateinit var uuid: UUID
    lateinit var level: ServerLevel
    lateinit var origin: Vec3i
    var rotation: Rotation = Rotation.NONE; protected set
    var intact: Boolean = false; protected set
    var dirty = false; internal set // cleared by AnionPersistence once the machine is written

    /** ship this Machine's core block sits on, null if grounded. owned by [StarshipMachines]. */
    // origin stays absolute even while attached — localToWorld() runs per-block per-isIntact(), so it
    // must never have to resolve through the ship. the ship pushes transforms down via relocate().
    var starship: Starship? = null; internal set

    /** ASYNC! Called every game tick. */
    abstract fun tick()
    /** ASYNC! Called every second. Agnostic of game tickrate, be wary of world state desyncs. */
    abstract fun slowTick()

    /** Returns a boolean if the Machine isIntact. */
    // default compares blockSet against the world at origin+rotation. override for dynamic
    // structures (door checks its own extension state, tank returns broken-wall vectors)
    // TODO: hybrid result type for isIntact, returns a boolean and then a list of not intact vectors.
    //       this could be used for tanks draining if a tank wall is broken, although specific impl is up to the dev.
    open fun isIntact(): Boolean {
        val set = blockSet ?: return true
        return set.blockMap.all { (offset, expectedVariants) ->
            val actualNms = (blockAt(offset).blockData as CraftBlockData).state
            expectedVariants.any { expected ->
                val expectedNms = (expected.blockData as CraftBlockData).state.rotate(rotation)
                actualNms == expectedNms
            }
        }
    }

    /** Called on Machine Disassembly */
    // recommended use: flush recipes, destroy buffers, call shutdown() to any attached entities or display components
    open fun onDisassemble() {

    }

    /** Called on Machine Assembly */
    // recommended use: init buffers is going to be automatic, as is port assignment,
    // so i guess handling specific logic bits not ordinarily present would be the main use case here....
    open fun onAssemble() {

    }

    /** Writes this Machine's own mutable state into [tag]. Base state (origin, rotation, type) is automatic. */
    // only write what cannot be re-derived: progress counters, door extension, buffered amounts. never
    // world blocks — the world is the source of truth and is already saved by the server.
    open fun saveState(tag: CompoundTag) {

    }

    /** Reads back whatever [saveState] wrote. [tag] is empty for a machine saved before this type had state. */
    open fun loadState(tag: CompoundTag) {

    }

    /** Called after the Machine has been moved or rotated by the ship carrying it. */
    // recommended use: fix up anything caching an absolute position that the base cannot know about
    // (display entities, spawned particles' anchors). blockSet offsets need no fixup — they resolve
    // through localToWorld() against the already-updated origin/rotation.
    open fun onRelocate() {

    }

    /** Registers a freshly placed Machine and gates its tick loop behind an initial intact check. */
    fun assemble(level: ServerLevel, origin: Vec3i, rotation: Rotation = Rotation.NONE): Machine {

        bind(UUID.randomUUID(), level, origin, rotation)
        activate()

        markDirty() // never been written to the database, so the save loop has to pick it up

        return this

    }

    /** Restores a Machine from the database. [tag] is whatever this type's [saveState] last wrote. */
    fun load(uuid: UUID, level: ServerLevel, origin: Vec3i, rotation: Rotation, tag: CompoundTag): Machine {

        // bind before loadState so a subclass restoring world-facing state can already resolve
        // level/origin/rotation, and activate after it so onAssemble() sees a fully restored machine.
        bind(uuid, level, origin, rotation)
        loadState(tag)
        activate()

        return this

    }

    /** Tears down a Machine: hook first, then deregister so late-firing tasks still see it as active. */
    fun disassemble() {

        onDisassemble()
        starship?.machines?.detach(this)
        activeMachines.remove(uuid)
        AnionPersistence.deleteMachine(uuid) // destroyed, not unloaded — the save goes with it

    }

    /** identity assignment, shared by assemble() and load(). */
    private fun bind(uuid: UUID, level: ServerLevel, origin: Vec3i, rotation: Rotation) {

        this.uuid = uuid
        this.level = level
        this.origin = origin
        this.rotation = rotation

    }

    /** registration tail, shared by assemble() and load(). */
    private fun activate() {

        activeMachines[uuid] = this
        Starship.starshipAt(level, origin)?.machines?.attach(this) // claimed if the core sits on a ship
        intact = isIntact()

        onAssemble()

    }

    /** Moves this Machine to [newOrigin] and composes [addedRotation] onto its own. Ship-driven only. */
    // base owns this the same way it owns assemble(): it is the single writer of origin/rotation after
    // assembly, so no subclass can move itself out from under the structure check.
    fun relocate(newOrigin: Vec3i, addedRotation: Rotation) {

        origin = newOrigin
        rotation += addedRotation

        onRelocate()
        markDirty()

    }

    /** Ticker entry point, called every game tick. */
    fun runTick() {

        if (starship?.moving == true) return // carrier is mid-rewrite, every world read would be torn

        if (intact) tick() // if it's intact instantly run tick()

    }

    /** Ticker entry point, called every second. Re-checks structure before allowing slowTick(). */
    fun runSlowTick() {

        if (starship?.moving == true) return

        // TODO: only update when marked as dirty, not every tick!
        intact = isIntact()    // every slowTick we recalculate the entire structure
        if (intact) slowTick() // if it's intact we tick

    }

    /** Re-runs the structure check immediately rather than waiting out the next slowTick. */
    // called by the carrier ship once a move settles, so a machine is not left reading as broken for
    // up to a second after riding a move it survived perfectly well.
    fun revalidate() {

        intact = isIntact()

    }

    /** [offset] relative to origin, rotation-applied -> absolute world position. */
    protected fun localToWorld(offset: Vec3i): Vec3i = origin + offset.rotate(rotation)

    /** World read at [offset], resolved through [localToWorld]. */
    protected fun blockAt(offset: Vec3i): BlockState {

        val pos = localToWorld(offset)
        return level.world.getBlockAt(pos.x, pos.y, pos.z).state

    }

    /** World write at [offset], resolved through [localToWorld]. Committed sync. */
    protected fun setBlockAt(offset: Vec3i, blockData: BlockData) {

        Tasks.runSync {
            val pos = localToWorld(offset)
            level.world.getBlockAt(pos.x, pos.y, pos.z).blockData = blockData
        }

    }

    protected fun markDirty() {
        dirty = true
    }

}
