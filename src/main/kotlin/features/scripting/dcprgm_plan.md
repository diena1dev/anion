# dcprgm implementation plan (v0.1)

Companion to `dcprgm_readme.md`. **That file is the spec and is owned by the developer — do not edit
it.** This file is the agreed build plan, written after a Q&A pass, so a fresh agent can pick the work
up without re-asking. Anything marked OPEN is still undecided.

**Status: v0.1 built, not yet tested in-game.** §10 lists exactly what landed where. The editor UI is
deliberately absent — the sign hooks it needs are in place and `MainframeMachine.onSignClick` is an empty
override with a TODO on it.

---

## 1. What dcprgm is

`dcprgm` = *datachannel program*. `dclang` = the language inside those files. A **mainframe** multiblock
stores one `main.dcprgm` per machine it is wired to, plus its own `groups.dcprgm`. Programs bind an
**input** on one machine to a **function** on another machine (or on a group of machines).

The mainframe is the only way to monitor or control a collection of machines. It works on ships **and on
the ground** — it is not a starship feature. Linking a reactor to its control panel is the same mechanism
as flying a ship.

## 2. Decisions locked in Q&A

| # | Decision |
|---|---|
| 1 | Mainframe is a `Machine` (multiblock, owns the file store and the editor entry point). |
| 2 | Mainframe scope is **physical wiring**, not proximity and not ship membership. Machines are reached over a data line network terminating on `COPPER_MACHINE_DATAPORT` cells. A data transport subsystem has to exist for this, and it also answers "is this machine still connected". |
| 3 | Two mainframes wired to the same machines each keep their **own disjoint set** — their own names, their own files. Overlap is legal; each mainframe commands independently. |
| 4 | Names are auto-generated when a machine is added to a mainframe: `<machine key>_<n>`, e.g. `medium_cargo_container_1`, then `_2`. |
| 5 | Editor UI is in-world signs on the mainframe driving Paper's dialog API. **The developer writes the UI.** This work only supplies: signs that are un-editable, signs that report clicks to their machine, and a compile-on-save API for the UI to call. |
| 6 | Floppy-disk items for backup/transfer are **out of scope** for v0.1. |
| 7 | One `main.dcprgm` per machine, exactly one `groups.dcprgm` per mainframe, no other files. The files **live on the mainframe**; per-machine files are references, not machine state. |
| 8 | v0.1 grammar is exactly: `def_group`, `set ... in ...`, `set ... to ... mode ...`, `//` comments. No arithmetic, no conditionals. |
| 9 | A machine's `available inputs` are values it *emits*. In v0.1 they are declared and broadcast; the only planned consumer (a sign readout) is v0.2. |
| 10 | `hold` — invoke every tick the key is down. `toggle` — flips a latched value with a **5 tick cooldown**, and the value stays on until clicked again. |
| 11 | Functions take **no arguments** in v0.1. Arguments land in v0.2 so displays can receive data. |
| 12 | Calling a function a machine does not have is a **no-op plus a warning**. |
| 13 | Programs **compile on save**, never per tick. |
| 14 | Control seat does not use a vehicle entity (sneak would dismount). The player is **locked in place** while the seat is active. |
| 15 | The `alt` modifier in the readme is **ctrl**, read off `PlayerInputEvent` / `Input`. |
| 16 | Debug thrusters keep redstone control **alongside** dcprgm. Future thrusters are primarily data-driven. |

## 3. Deliverables

Three subsystems, in dependency order:

1. **Data transport** — chains carry data, one custom junction block, network walk from a mainframe.
2. **Machines** — `MainframeMachine`, `ControlSeatMachine`, and the `DcProgrammable` capability interface.
3. **dclang** — parser, compiled program, runtime dispatch, plus sign plumbing for the developer's UI.

---

## 4. Data transport

### Blocks

- **Data line: the vanilla iron chain.** Vanilla blocks cannot implement `AnionTransportComponent`, so it
  is adapted in `AnionTransportComponents.byMaterial` — `Material.CHAIN to DataLine` — exactly the way
  `HOPPER`/`CRAFTING_TABLE` already are. Chain has an `axis`, so it carries end-to-end like `ITEM_PIPE`.
- **Data junction: one new `AnionBlock`.** The single custom block this feature is allowed. Any side in,
  any other side out, same shape as `AnionItemPipeJunctionBlock`. Registered in `AnionBlocks` on
  `Instrument.ZOMBIE`, next free note (18 at time of writing — verify).
- **Endpoints are `COPPER_MACHINE_DATAPORT` cells**, i.e. `MachinePort.Kind.DATA`. No new port kind.

### Keeping items out of the data network

Both data components implement `AnionTransportComponent` for geometry, and both override:

```kotlin
override fun accepts(block: Block, resource: AnionResource): Boolean = false
```

`AnionTransport.search()` consults `accepts` before `exitsFor`, so the item pass dead-ends at a chain
instead of routing cargo down it. Neither component overrides `drive` — data is not moved per pass.

### The walk

New `features/transport/DataNetwork.kt`:

```
fun reachableDataPorts(level, fromPortCells: Set<Vec3i>): Map<Vec3i, MachinePort>
```

BFS out of the mainframe's own DATA port cells, stepping through components via
`AnionTransportComponents.at(block).exitsFor(...)`, terminating on any cell holding another machine's DATA
port. Bounded by a max node count (mirror `MAX_ROUTE_LENGTH`, larger). Runs on the mainframe's
`slowTick()`, not on `AnionTransport.tick()` — `AnionTransport.tick()` stays item-only and is not touched.

DATA ports are found the way `AnionTransport.portsIn()` finds bus ports: iterate
`Machine.activeMachines`, filter `PortedMachine`, filter `kind == DATA`, `localToWorld` each offset. Built
per walk rather than indexed, so ship-carried machines are picked up.

---

## 5. Machines

### `DcProgrammable` — capability interface

Per MACHINE_README rule 2: a capability interface checked with `is`, no new inheritance layer.

```kotlin
interface DcProgrammable {
	/** values this machine emits. declared in v0.1, consumed in v0.2. */
	val dataInputs: List<String> get() = emptyList()
	/** function names other machines may call on this one. */
	val dataFunctions: List<String> get() = emptyList()
	/** [active] is the latched/held bit. no arguments until v0.2. */
	fun invoke(function: String, active: Boolean) {}
}
```

The header lines the readme shows (`available inputs:`, `available functions:`) are rendered straight off
these two lists — there is no second declaration to drift out of sync.

Thrusters implement it later (`increase_throttle`, `decrease_throttle`, `toggle`, `reset`) and keep their
`portSignal(DATA)` redstone path.

### `MainframeMachine` (`features/scripting/machines/MainframeMachine.kt`)

A `PortedMachine`. Owns:

- `attached: Map<String, UUID>` — assigned name to machine uuid. **Persisted** in `saveState`. A name is
  never reused and never reassigned, so a program referencing an unplugged machine survives replugging.
- `programs: Map<String, String>` — machine name to `main.dcprgm` source. Persisted.
- `groupsSource: String` — the `groups.dcprgm` text. Persisted.
- compiled artefacts (`DcProgram` per machine, `DcGroups`), **not** persisted — rebuilt on load.
- `slowTick()` re-walks the network: newly reached machines get a name, machines no longer reached are
  marked offline but keep their name and their file.
- `dispatch(sourceName, inputName, active)` — the entry point the control seat calls.

Structure: casing + dataports + vanilla wall signs. `BlockMatcher.Vanilla` matches on material alone, so
sign facing/waterlogging does not affect the structure check. Use `TEST_BLOCK` for any cell that would
otherwise need a new texture.

### `ControlSeatMachine` (`features/scripting/machines/ControlSeatMachine.kt`)

Small machine, `DcProgrammable` with `dataInputs` = the readme's input list.

- Right-click to take the seat; a stored `pilot: UUID?` and the seat's location.
- Player is **locked in place**: teleport back to the seat anchor each tick, keeping their own yaw/pitch so
  they can still look around. No vehicle entity — sneak must stay usable as an input.
- Inputs per tick from `player.currentInput`: `w/a/s/d` = forward/backward/left/right, `sp` = jump,
  `sh` = sneak, **ctrl = `Input.isSprint`** — that is the `alt` modifier the readme wants.
- `lc`/`rc` come from events while seated, not from `Input`: `PlayerAnimationEvent` (arm swing) for `lc`,
  `PlayerInteractEvent` right-click for `rc`. Both are edge signals; see risks.
- Each tick, the seat diffs its input bitset and calls `mainframe.dispatch(...)` for what changed or is
  still held.
- The seat finds its mainframe by asking the network which mainframe named it. Cache the uuid, revalidate
  on slowTick.

---

## 6. dclang (`features/scripting/`)

```
DcProgrammable.kt   capability interface (above)
DcParser.kt         source text -> DcProgram | List<DcError>
DcProgram.kt        compiled: Map<inputName, List<DcBinding>>; DcBinding(target, function, mode)
DcGroups.kt         compiled groups.dcprgm: Map<groupName, Set<machineName>>
DcRuntime.kt        latch/hold state per (machine, input); resolves a binding to live machines and invokes
DcStore.kt          per-mainframe file store: read, write, compile-on-save, report errors
```

### Grammar (v0.1, complete)

```
// comment
def_group [name]
set [a] and [b] in [group1] and [group2]        // cross product, per the readme
set [input] to [target:function] mode [toggle|hold]
```

`target` is a group name or a machine name. All identifiers are bracketed. Statement per line.

### Compile

`DcStore.save(name, source)` parses, and on success swaps in the new `DcProgram`. On failure it returns
errors to the caller (the dialog UI) and **leaves the previously compiled program live**, so a typo does
not disarm a flying ship. Unknown machine or group name is a compile error at save time; a function a real
machine does not have is a runtime no-op plus a warning (decision 12).

### Runtime

`DcRuntime` holds, per mainframe, the mode state for every bound input:

- `hold` — `invoke(fn, true)` every tick the key is down, one `invoke(fn, false)` on release.
- `toggle` — a press flips a latched bool, subject to a **5 tick cooldown**; while latched on, the value is
  continuously on. **ASSUMPTION:** "continuously on" is implemented as `invoke(fn, true)` every tick while
  latched, mirroring hold, with one `invoke(fn, false)` on the tick it latches off. Confirm before relying
  on it — the alternative is a single edge call per flip.

Dispatch resolves a binding's target through `DcGroups` to a set of names, each name through
`MainframeMachine.attached` to a live `Machine`, and calls `invoke` on the ones that are `DcProgrammable`.
Offline or missing machines are skipped silently; a live machine missing the function warns.

---

## 7. Sign plumbing (developer builds the UI on top)

New `features/machine/component/MachineSign.kt` plus listener entries in `AnionMachineListeners`:

- **Un-editable** — cancel the sign-open/edit event for any sign cell belonging to an assembled machine's
  `resolvedStructure`.
- **Clickable** — a right-click on such a sign resolves the owning machine and its local offset, then calls
  an open hook: `open fun onSignClick(offset: Vec3i, player: Player, front: Boolean)`.
- **Writable** — `machine.setSignLines(offset, lines)`, committed through `Tasks.runSync`, so the developer's
  dialog code can render state back onto the mainframe face.

Nothing about the dialog UI itself is in scope. `MainframeMachine.onSignClick` gets a stub with a TODO.

---

## 8. Wiring checklist

- `AnionBlocks` — one `registerBlock` for the data junction.
- `AnionTransportComponents.byMaterial` — one entry, `Material.CHAIN to DataLine`.
- `AnionMachines` — `registerMachine { MainframeMachine() }`, `registerMachine { ControlSeatMachine() }`.
- `AnionMachineListeners` — sign edit cancel, sign click, seat click, seat input events, pilot quit/death.
- `Anion.kt` — **no new tick loop.** Mainframe and seat are machines and ride the existing machine tick and
  slowTick passes.

## 9. Risks and open items

- **OPEN (decision 4):** the readme's Q&A said a second `medium_cargo_container` becomes `mcc_2`. Plan
  assumes that was shorthand and implements `medium_cargo_container_2`. Confirm whether names abbreviate.
- **OPEN (decision 10):** the toggle assumption in §6 above.
- **`rc` hold is not reliable.** Minecraft does not stream a right-click-held signal for an empty hand, so
  `rc` is press-edge only in v0.1. `mode [hold]` on `rc` will be accepted by the parser and behave like a
  one-tick pulse — flag it in `/dcprgm debug` rather than making it a compile error.
- **`lc` hold** rides repeated arm-swing packets, so it needs a short decay window (~4 ticks) or it will
  stutter.
- Locking the pilot by teleporting every tick fights client prediction. If it rubber-bands badly, the
  fallback is an invisible marker entity the player rides with dismount cancelled, at the cost of sneak.
- `AnionTransport` has no cached network graph (its own TODO). The data walk has the same problem and the
  same fix; a mainframe re-walking once per second is fine at prototype scale.
- Per the readme's own note: bulk-assigning input channels from one group is **not** in v0.1. Receivers are
  assigned individually and that is the accepted compromise.

---

## 10. What was built

Machines went to `features/machine/machine_types/scripting/` rather than under `features/scripting/`, so
every `Machine` subclass stays in one tree the way the thrusters and containers already do. The language
and its runtime stayed in `features/scripting/`.

| file | what it is |
|---|---|
| `features/transport/AnionDataComponents.kt` | `AnionDataComponent` marker, `DataLine` (vanilla chain), `AnionDataJunctionBlock` |
| `features/transport/DataNetwork.kt` | `reachableFrom(machine)` BFS + `describe()` |
| `features/transport/AnionTransportComponent.kt` | gained `val indexed` |
| `features/transport/AnionTransportIndex.kt` | `register` now asks `indexed` |
| `features/transport/AnionTransportComponents.kt` | one `byMaterial` line for `CHAIN` |
| `features/scripting/DcProgram.kt` | `DcMode`, `DcBinding`, `DcProgram`, `DcGroups`, `DcIssue`, `DcResult` |
| `features/scripting/DcParser.kt` | the whole v0.1 grammar |
| `features/scripting/DcProgrammable.kt` | capability interface |
| `features/scripting/DcRuntime.kt` | latch/hold state, dispatch, once-per-binding warnings |
| `features/scripting/DcStore.kt` | file store, compile-on-save, recompile |
| `features/machine/component/MachineSign.kt` | `ownerAt`, `write`, `seal` |
| `features/machine/Machine.kt` | gained `open fun onSignClick(offset, player, front)` |
| `features/machine/machine_types/scripting/MainframeMachine.kt` | structure, naming, walk, persistence, rename |
| `features/machine/machine_types/scripting/MainframeConsole.kt` | the three-sign menu state machine |
| `features/machine/machine_types/scripting/MainframeDialogs.kt` | Paper dialogs: editor, groups editor, rename |
| `features/machine/machine_types/scripting/ControlSeatMachine.kt` | pilot lock, input reading |
| `features/listeners/AnionScriptingListeners.kt` | sign edit cancel, sign click, seat mount/dismount, clicks, quit |
| `features/custom/blocks/AnionBlocks.kt` | `DATA_JUNCTION`, note 18 |
| `features/machine/AnionMachines.kt` | `MAINFRAME`, `CONTROL_SEAT` |
| `features/machine/machine_types/thrusters/DebugThrusterHorizontal.kt` | now `DcProgrammable` |

### Decisions taken while building, not in the Q&A

- **Data lines are not indexed.** `AnionTransportComponent.indexed` is new: chains and junctions return
  false, so a decorative chain is never written to the transport column family and never visited by the
  item pass. Everything that existed before returns true and is unaffected.
- **Signs are waxed on assembly** (`MachineSign.seal`) as well as guarded by cancelling
  `PlayerOpenSignEvent`, because an axe can strip wax back off.
- **An offline machine's inputs go unchecked** rather than failing its program to compile. Without this a
  server restart would drop every file on the ship, since machines load in an arbitrary order.
- **A program that stops compiling is dropped, its source is kept.** Only a `groups.dcprgm` edit can do
  this — machine names are never released, so unplugging a machine cannot break a program that names it.
- **`DebugThrusterHorizontal` is the one receiver wired up**, so the chain is testable end to end. It gains
  `increase_throttle`/`decrease_throttle`/`toggle`/`reset` and takes `max(redstone, datachannel)`, which
  is decision 16's "alongside". The other two thrusters are untouched.
- **Toggle latches live on the calling side.** A machine's `toggle` function just follows `active`, so it
  mirrors whatever the mainframe latched rather than keeping a second latch of its own.
- **A seated pilot is frozen, not just held.** `walkSpeed` and `flySpeed` go to 0 and the
  `Attribute.JUMP_STRENGTH` base value goes to 0, all restored on standing up. The teleport-back is still
  there as the backstop. `Input` reads which keys are *pressed*, not what they moved, so every input keeps
  reporting while the player cannot move an inch. If a server build ever lacks jump_strength on players
  the attribute lookup returns null and only jumping stops being blocked.
- **Frozen players are repaired on join.** Speeds live in player data, so a server that goes down with
  somebody seated brings them back unable to move. `ControlSeatMachine.repairFrozen` restores anyone who
  joins at exactly zero on both speeds with no seat holding them.

### Structures

Mainframe is 3x3x2: casing, a dataport up the back at both heights, three oak wall signs across the front
of the top course, `TEST_BLOCK` core. Control seat is 1x1x2: a dataport with polished deepslate stairs on
top, stairs are the core. Both are placeholder shapes — no new textures were added beyond the junction.

### Console and editor

Three signs across the front, state held per console rather than per player — they are one physical
object, so two people at the same mainframe see the same page. Sign identity is by local offset
(`SIGN_LEFT = Vec3i(1, 1, -1)`), so left and right stay the reader's left and right after a rotation.

| page | left | centre | right |
|---|---|---|---|
| MAIN | `MACHINES >>` → LIST | `n machines connected` | — |
| LIST | `<<` | machine name + `i/n`, click to select | `>>` |
| SELECTOR | `RENAME` | name, file/online state, click for LIST | `EDIT main.dcprgm` |

The list wraps at both ends and carries two extra entries after the machines: `groups.dcprgm`, which
opens the mainframe's own file directly (it has no machine behind it, so it skips the selector), and
` back`, which returns to MAIN.

**Only connected machines are listed.** A name stays assigned forever so a program survives its machine
being unplugged, but the console reads `onlineNames`, not `machineNames` — a list of everything ever
attached is unreadable. The consequence: an unplugged machine's file cannot be opened until it is wired
back in. The file itself is untouched and comes straight back with it. The `available machines:` header
in the groups editor is filtered the same way; a group already written against an absent machine still
compiles, because the parser is still handed every known name.

If the selected machine unplugs while the selector page is open, the console falls back to the list. If
the list shortens under the cursor, the cursor clamps.

Dialogs are Paper's, built in `MainframeDialogs` with the callback form of `DialogAction.customClick`, so
no registry key or `PlayerCustomClickEvent` handler is involved:

- **Editor** — `DialogType.confirmation(save, discard)` with one
  `DialogInput.text(...).multiline(MultilineOptions.create(256, 320)).width(1024).maxLength(16384)`.
  The body carries the readme's header lines (`available inputs:`, `available functions:`,
  `available modes:`, `available groups:`) read off `DcProgrammable`.
  **Confirmation rather than multiAction because of where the buttons land**: a confirmation renders both
  of its buttons in the dialog footer, which is pinned to the screen, while a multiAction's action list
  scrolls with the body and walks off the bottom at small gui scales. Only its `exitAction` is in the
  footer. Anything wanting a third editor button has to take that into account.
- **Rename** — `DialogType.confirmation` with a single-line text input.
- **A save that does not compile re-opens the editor** holding what the player typed, errors listed in
  red above the box. Warnings are chat messages on a successful save.

### Not done

- Floppy disks (decision 6).
- Nothing consumes `dataInputs` values, and functions take no arguments (decisions 9 and 11, both v0.2).
- Only `DebugThrusterHorizontal` is a receiver.
- No way to delete a file from the console. `store.forget(name)` exists and has no caller.
