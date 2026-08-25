# Machine System

Machines are multiblock structures that own their tick logic and structure detection, built from
shared helper classes. The design blends two existing patterns in Anion:

- **AnionItem** — configuration flows up through constructor parameters, optional lambda handlers
  let simple cases skip subclassing entirely, and a central dispatcher routes events to instances.
- **Starship** — logic is split into single-concern helper classes (`StarshipHitbox`,
  `StarshipVelocity`, `StarshipSimulator`) that hold a back-reference to their owner and are
  created via `Helper.new(this)`.

---

## Type vs Instance

The single most important structural decision: **a subclass is a type, an object is a placement.**

- The class hierarchy carries *behavior* and *static config* (door shape, materials, BlockSet),
  passed up through constructor parameters exactly like AnionItem does.
- Every assembly (wrench on core block) constructs a **new instance** with its own UUID, origin,
  rotation, and runtime state, registered in `Machine.activeMachines`.
- Persistence reload uses one small registry: `namespacedKey -> () -> Machine` factory, so a saved
  machine can be rebuilt by key. This is the *only* registry the system needs.

## The Two Rules (keeping OOP flat)

### Rule 1 — Subclass only for new *behavior*, never for config

Machines that differ only by numbers, materials, or shape are **one class with different
constructor arguments**, not different classes:

```kotlin
// no BlastDoorMachine or HangarDoorMachine classes exist.
val BLAST_DOOR  = { DoorMachine("Blast Door",  BLAST_DOOR_SET, 3, 4, 1, STEEL_STATE) }
val HANGAR_DOOR = { DoorMachine("Hangar Door", HANGAR_SET,     9, 6, 1, GLASS_STATE) }
```

For one-off logic tweaks, optional lambda handlers (AnionItem's `interactHandler` pattern) let a
registration customize `tick`/`slowTick` without a subclass at all.

### Rule 2 — Shared features live in helpers, not superclass chains

Cross-cutting features (displays, data channels, energy, inventories) are **fields**, never
inheritance layers. There is no `PoweredMachine -> PoweredInventoryMachine` combinatorial
explosion; any machine mixes any set of helpers. A new feature is a new helper class plus one
field — zero new hierarchy layers.

Opt-in capabilities are marked with interfaces whose state lives in the helper:

```kotlin
interface EnergyUser { val energy: MachineEnergy }   // state in helper, not interface

class PulverizerMachine(...) : Machine(...), EnergyUser {
    override val energy = MachineEnergy.new(this)
}
```

External code checks `if (machine is EnergyUser)` — no intermediate class ever exists.

### Resulting depth

| Depth | Case |
|-------|------|
| 1 | Most machines: extend `Machine` directly, config via constructor |
| 2 | A machine *family* with real shared state + behavior (e.g. `DoorMachine`'s animation solver) |
| 3 | Does not happen. If tempted, the shared piece belongs in a helper or interface instead |

## Class Structure

```
Machine (base — type = subclass, placement = instance)
│
├─ concrete machines: direct instantiation, config + optional lambdas   (depth 1)
├─ machine families:  DoorMachine, ... (shared state + behavior only)   (depth 2)
│    └─ registrations: "Blast Door", "Hangar Door" (params, no class)
│
├─ helpers (composition, Helper.new(this)):
│    ├─ MachineBuffers   — declared resource storage, available()/commit() for recipes
│    ├─ MachinePorts     — resource ports, re-derived from placed blocks
│    ├─ MachineDataPorts — data ports, emission only until a transport exists
│    ├─ MachineDisplay   — buffered text displays, sync flush on slowTick
│    ├─ MachineData      — DataChannel get/set, UUID links between paired machines
│    └─ (future: MachineInventory, ...)
│
└─ capability interfaces: EnergyUser, ... (checked via `is`, state held in helper)
```

## Lifecycle & Tick Pipeline

The base class owns a **final pipeline** (`fun`, cannot override) that guarantees registration,
intact-gating, and helper cleanup always happen. Subclasses only implement **hooks**
(`open`/`abstract`):

```
assemble()    -> solve rotation, register, init helpers, persist, then onAssemble()
runTick()     -> if (intact) tick()
runSlowTick() -> intact = isIntact(); if (intact) slowTick(); display.flush()
disassemble() -> onDisassemble(), display.shutdown(), data.unlinkAll(), deregister, delete save
```

- **Structure checks run on slowTick, not tick.** The cached `intact` flag gates the fast tick.
  A later optimization can replace polling entirely: a global `Vec3i -> Machine` occupancy map fed
  by block-break listeners flips `intact` instantly.
- **`isIntact()` is open with a default implementation** — the default compares `blockSet` against
  the world at `origin` + `rotation` (covers most machines for free). Dynamic structures override
  it: a door checks frame + door blocks *based on its own extension state*; a tank can return
  broken-wall vectors.

## Threading

`tick()` and `slowTick()` are **async**. Machine logic computes off-thread; helpers own the sync
boundary and commit world mutations through `Tasks.runSync` (display flushes, door block
placement). One central `Tasks.scheduleAsync` loop iterates `activeMachines` — machines never
self-schedule, so there is one place to profile and one place where `logExceptions` catches
throws.

## Data Transmission (control panels)

`MachineData` is the transport between paired machines:

```kotlin
class DataChannel<T : Any>(val key: NamespacedKey)

machine.data.set(DOOR_TARGET, 4)      // control panel writes, pushed to linked machines
machine.data.get(DOOR_TARGET)         // door reads in tick(), steps toward target
machine.data.link(otherMachine)       // links stored as UUIDs -> survive serialization
```

Pull-in-tick semantics by default; an optional `onDataChanged(channel)` hook can add push
semantics later. Machines declare supported channels; `null` opts out, and all get/set methods
have null handlers.

## Worked Example: Doors

```kotlin
open class DoorMachine(
    displayName: String,
    blockSet: BlockSet,                 // frame + control surface
    val doorWidth: Int,
    val doorHeight: Int,
    val doorDepth: Int,
    val doorMaterial: BlockState,
) : Machine(displayName, blockSet) {

    var extension: Int = 0              // rows currently extended — partial state IS this int

    fun extend(rows: Int = 1)  { /* compute target, queue sync placement */ }
    fun retract(rows: Int = 1) { /* inverse */ }

    override fun isIntact(): Boolean = TODO("frame check + door check for current extension")
    override fun tick() { /* step extension toward data.get(DOOR_TARGET) */ }
    override fun slowTick() { display.line("state", Component.text("$extension/$doorHeight")) }
}
```

A `DoorControlMachine` transmits on the same channel. Partial opening is just the `extension`
integer stepping toward the channel target each tick.

## Ports & Buffers

Components live in `machine/component/`. A machine opts in by declaring `bufferCapacities` — one
storage slot per entry; passing null costs it nothing, not even the per-slowTick port verification.

**Slots are not declared against a resource.** A slot starts free, latches onto the first resource
piped into it, and refuses anything else until released. The latch survives the slot running empty —
otherwise draining a buffer would silently free it for something else to grab mid-operation. So a
machine declares how *much* it can store, never *what*.

Releasing a slot is a player action: **sneak + right-click a port with an empty hand**. It voids the
contents and frees the slot, scoped by the port's `PortKind`, so clearing through a bus frees item
slots and leaves a fluid slot alone. The empty-hand requirement exists because the block dispatcher
replays item use after the hook — sneak-clicking with a block held is a placement and must not also
wipe storage.

**Buffers store, recipes consume, ports expose, transport moves.** A `MachinePort` is a passive
accessor — no mode, no direction, no bound resource. The caller names the resource and picks
`insert`/`extract`; `PortKind` only decides what can physically pass.

**Nothing in this layer meters speed.** `AnionPortBlock.throughput` is a *capacity rating*, not a
per-tick rate, and `Machine.maxTransfer` is how much total port a machine can physically support:

```
sum(installed port throughput) <= machine.maxTransfer
```

Four 20-capacity ports on an 80-capacity machine assemble fine. A fifth does not — the machine
refuses to assemble, and a live one breaks the moment the extra port goes in. A `BlockSet` places no
ceiling on how many port cells a structure has, so this is what bounds a build.

Capacity is checked as part of the structure, alongside the block check: `assemble()` returns null
when over capacity — the one structural fault that refuses assembly outright rather than assembling
as not-intact, since it is a deliberate build choice. It is an **assembly-time check only**. Ports
cannot change while a machine is assembled (see below), so the sum cannot drift, and there is nothing
for `runSlowTick` to re-check.

Rate, where it exists at all, lives outside: `AnionIngredient.ratePerTick` on the recipe side, and
whatever the transport layer decides on the other. Buffers stay pure storage throughout.

Buffers decouple burst from sustained rate. Under continuous draw a buffer bottoms out and
throughput settles at what the ports supply, so the README's starvation behaviour is emergent rather
than special-cased. `MachineBuffers.available()` / `commit()` are shaped to `MachineRecipeAdapter`'s
existing contract.

### Ports are derived from the world, then locked

A structure that maps casing, bus, valve and dataport onto one character is saying "the player
chooses what goes here" — so the port layout cannot come from the `BlockSet`, only from what was
actually built. Ports are keyed by **local offset**, so they ride ship moves and rotations without
invalidation.

**The layout is fixed at assembly.** `MachinePort.derive()` walks the structure once, during
`assemble()` and again during `load()`, and the resulting set is the machine's port layout for as
long as it stays assembled. Every slowTick after that only **verifies** it — a port swapped for a
different kind, removed, or added invalidates the machine instead of quietly re-deriving:

```kotlin
intact = isIntact() && ports == MachinePort.derive(this)
```

This is a separate step because the block check cannot catch it: a cell that accepts a bus *or* a
valve accepts either one, so swapping a gas valve for an item bus passes `isIntact()` untouched while
silently repointing where the machine's contents can go. Changing ports means disassembling first.

The verification pass is bounded by the `BlockSet`, not by the whole structure. `BlockSet.portCells`
is the set of offsets whose accepted variants include a port block — type config, computed once per
machine type — so verification walks only cells that could ever hold a port. Checking just the
already-known ports would be cheaper still, but would miss a bus dropped into a casing cell and leave
the player with a port block the machine ignores.

Machines with no `BlockSet` have no `portCells` to bound by and own port discovery outright, the same
way they already own `isIntact()`.

Buffer contents persist through the base's `saveTo()`, written **positionally** — a slot's index in
the declared capacities is its identity, so reordering that list reshuffles saved contents the way
any indexed inventory would. Each entry stores the latched resource key alongside the amount, and a
key that no longer resolves leaves that slot free rather than failing the whole load. Ports carry no
state and are never written: locked layouts make the set derived at `load()` identical to the one
`assemble()` built, so the world remains the only record of it. Dataports have discovery and `emit()`
only —
`MachineDataPorts.transport` is the seam where routing lands.

## Persistence

`MachineSerializer` stores one blob per instance in the `machines` column family, keyed by machine
UUID: schema version, world UUID, absolute origin, rotation, registry path, then whatever
`saveState(tag)` wrote. Loading mirrors starships — `AnionMachineListeners` scans the CF on
`ChunkLoadEvent` and restores every machine whose core block falls in that chunk.

Carrier ships are **not** stored. Which ship owns a machine is fully derivable from the ship's
`blockHashMap`, so the link is re-established by whichever side loads second (`Starship.load` calls
`machines.rebuild()`, `Machine.load` calls `Starship.starshipAt`). The starship blob's machine-ref
count is pinned at 0 and kept only to hold the on-disk layout stable.

`disassemble()` deletes the row (the machine was destroyed); `AnionPersistence.unloadMachine` only
saves and drops it from memory.

## Starships

Machines ride moving ships. `StarshipMachines` is the ship-side helper holding the carried set;
`Starship.move`/`rotate` push transforms down through `Machine.relocate`, which composes rotation and
fires `onRelocate()` for anything caching an absolute position. Machines freeze (`runTick` /
`runSlowTick` early-return) while `Starship.moving` is set, and `revalidate()` re-checks structure
once the move settles.

## Open Implementation Chunks

1. **Rotation solving** — structure detection must try all 4 rotations around the core block when
   the wrench is used, storing the winning `Rotation` on the instance (see `BlockSet` notes in
   the main README).
2. **`onUnload()` hook** — `unloadMachine` deliberately skips `onDisassemble()`, so a machine holding
   attached entities has no way to shut them down when its chunk unloads.
3. **Cross-world moves** — `Starship.changeWorld` must reassign `level` on carried machines, not just
   their origin. `relocate()` only moves within a level.
