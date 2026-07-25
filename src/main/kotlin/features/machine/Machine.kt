package dev.diena.anion.features.machine

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/// Machine member inventory — every variable and function of the base class,
/// grouped by who supplies it and who is allowed to touch it. see MACHINE_README.md
//|
//| [CONSTRUCTOR — type config. val, immutable. one set of values per registered machine type]
//|- displayName: String
//|- blockSet: BlockSet                                  // structure definition, compared at origin+rotation
//|- namespacedKey: NamespacedKey = adaptedFromDisplayName // AnionResource override
//|- dataChannels: Map<NamespacedKey, DataChannel<*>>?   // must specify opt-out with null, all get/set methods have null handlers
//|- tickHandler: (Machine.() -> Unit)? = null           // optional: simple machines skip subclassing entirely
//|- slowTickHandler: (Machine.() -> Unit)? = null
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
abstract class Machine {

    companion object {
        val activeMachines: ConcurrentHashMap<UUID, Machine> = ConcurrentHashMap()
    }

    fun test() {}

    /** ASYNC! Called every game tick. */
    abstract fun tick()
    /** ASYNC! Called every second. Agnostic of game tickrate, be wary of world state desyncs. */
    abstract fun slowTick()

    /** Returns a boolean if the Machine isIntact. */
    // specific implementation is up to the Machine dev, but best practice is to halt Machine processing until this is true.
    // TODO: hybrid result type for isIntact, returns a boolean and then a list of not intact vectors.
    //       this could be used for tanks draining if a tank wall is broken, although specific impl is up to the dev.
    abstract fun isIntact(): Boolean

    /** Called on Machine Disassembly */
    // recommended use: flush recipes, destroy buffers, call shutdown() to any attached entities or display components
    open fun onDisassemble() {

    }

    /** Called on Machine Assembly */
    // recommended use: init buffers is going to be automatic, as is port assignment,
    // so i guess handling specific logic bits not ordinarily present would be the main use case here....
    open fun onAssemble() {

    }

}
