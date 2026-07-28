package dev.diena.anion.features.machine

import dev.diena.anion.Anion
import dev.diena.anion.Tasks
import dev.diena.anion.extensions.plus
import dev.diena.anion.extensions.rotate
import org.bukkit.craftbukkit.block.data.CraftBlockData
import dev.diena.anion.features.custom.AnionResource
import net.minecraft.core.Vec3i
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
//|- disassemble()               // onDisassemble(), display.shutdown(), data.unlinkAll(), deregister, delete save
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
//\- onDisassemble()    open      extra teardown: flush recipes, destroy buffers, shutdown attached entities
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
    var dirty = false; protected set

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

    /** Registers a freshly placed Machine and gates its tick loop behind an initial intact check. */
    fun assemble(level: ServerLevel, origin: Vec3i, rotation: Rotation = Rotation.NONE): Machine {

        this.uuid = UUID.randomUUID()
        this.level = level
        this.origin = origin
        this.rotation = rotation

        activeMachines[uuid] = this
        intact = isIntact()

        onAssemble()
        return this

    }

    /** Tears down a Machine: hook first, then deregister so late-firing tasks still see it as active. */
    fun disassemble() {

        onDisassemble()
        activeMachines.remove(uuid)

    }

    /** Ticker entry point, called every game tick. */
    fun runTick() {

        if (intact) tick() // if it's intact instantly run tick()

    }

    /** Ticker entry point, called every second. Re-checks structure before allowing slowTick(). */
    fun runSlowTick() {

        // TODO: only update when marked as dirty, not every tick!
        intact = isIntact()    // every slowTick we recalculate the entire structure
        if (intact) slowTick() // if it's intact we tick

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

        val pos = localToWorld(offset)
        Tasks.runSync { level.world.getBlockAt(pos.x, pos.y, pos.z).blockData = blockData }

    }

    protected fun markDirty() {
        dirty = true
    }

}
