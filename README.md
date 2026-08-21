project structure

```
main
|- command
|   |- admin
|   \- utils
|- extensions
|- features
|   |- custom
|   |   |- blocks
|   |   |- energies
|   |   |- fluids
|   |   |- gasses
|   |   \- items
|   |- listeners
|   |- machine
|   |   |- component            (MachineBuffer, MachinePort)
|   |   |- examples             (BlinkerMachine)
|   |   \- machine_types        (PortedMachine, PortedTankMachine)
|   |       \- basic_test_machine
|   |- recipes
|   |   \- adapters             (crafting, furnace, machine)
|   \- starship
|       \- simluated
\- data
    |- database                 (RocksDB, serializers, migrators)
    |- datagen
    |   \- resourcepack
    \- registry
        |- keys
        \- registries
```

---
MACHINES

how do we want to handle data.... ion uses text displays and physical, in-world state (inventories, pdc of text displays (iirc)).
this has flaws, though: text displays must be loaded when being used, it's unnecessary extra overhead.

- TERF machine cores are janky, but work,
- Ion's sign detection is fine, and works well enough....
- But I want to make a new system: one that can operate WITHOUT a machine core representation or a sign!

**idea: place down all the blocks for a multiblock, then interact with a wrench to assemble it.**

an assembled machine remains active until a block on it is broken or moved, then it becomes inactive again.
mimicking mekanism's particles for this effect would be quite cool: the redstone particles emitted around an assembled or broken machine.

however! signs should still totally be used to display information and text about the machine (look at KBUSJ's anemone machine screens for inspiration).
machines should also be packageable, like what Ion has.

---
POWER

- machines should take a fixed amount of power from a source, like a circuit. if a power line is starved of voltage, the machine stalls.
- stalling can slow down or completely halt a machine, depending on the configuration.
- BUT THE BASIC GIST IS THAT A MACHINE TAKES A FIXED AMOUNT OF POWER, AND IF THE LINE DOES NOT HAVE ENOUGH OF ENERGY AVAILANLE IT WILL STALL. LINES MUST HAVE A CONSTANT SUPPLY OF THIS POWER!

-# Slow vs halt falls out of the recipe math rather than being a separate config flag. `MachineRecipeAdapter` finds the bottleneck ratio across every ingredient: a partially starved line drops that ratio and the machine runs slower, an ingredient at literally zero makes the ratio zero and the machine stops dead that tick. Draw and progress are the same operation — the adapter debits the buffer and credits progress with whatever the buffer actually gave up, so the two can never drift apart.
Idle power draw is just a recipe with no item result, per the "if another subsystem already does this, use it" rule.
Nothing supplies energy yet. Until the energy and transport subsystems land, a machine should hand the adapter a supply function that reports unlimited AnionEnergy — TODO in MachineRecipeAdapter.


---
TRANSPORT

first pass, items only.

**components drive, ports do not.** a MachinePort exists only to bridge to an internal buffer, so transport never iterates ports — it iterates its own blocks and looks a port up when one happens to be on the other side. the adapters are the intermediary blocks that attach on the outside of a port: a chute on a bus, and later a pump on a valve and a connector on a conduit.

**the block is the behaviour.** `AnionTransportComponent` is an interface a block implements — `exitsFor`, `drive`, `describe` — so `AnionTransport.tick()` resolves the component and calls the hook without ever naming a block, the way AnionItemDispatcher does for items. a driver gets a `TransportPass` rather than the world, so the per-buffer rationing cannot be skipped. a new component is a new class plus one line in `AnionBlocks`; editing AnionTransport to add one means it is in the wrong place. vanilla blocks cannot implement an interface, so the crafting table is adapted through `AnionTransportComponents.byMaterial`.

nothing in that interface says "item". what it does not yet cover is a component that *holds* its contents instead of passing them straight through — a gas pipe that fills up and flows on. an AnionBlock is a singleton shared by every placed copy, so per-cell contents need an instance layer like `Machine.activeMachines`, keyed by cell or by network. that is the same graph the cached-routing TODO wants, and it should land with gas rather than before it.

drivers:
- **chute** — exports the buffer of any bus port touching it, out of any of its other sides. no facing: the name suggests one, but making it directional only ever created a way to build it backwards. with no port beside it, it carries like a junction.
- **crafting table** — the vanilla-inventory import adapter. pulls from any container touching it and pushes into whatever the network offers. asymmetric on purpose: a chest feeds the network through a table rather than being drained by any pipe that happens to run past.

**throughput** is rationed per buffer per pass, not per driver. `transferLimit()` is `softTransfer` per bound port clamped by `hardTransfer`, so more ports genuinely is more throughput — the point of ports — but a wall of importers cannot stuff or drain a buffer in one tick. both ends of a handoff are charged, so neither a busy source nor a popular destination gets worked harder than its own ports allow. a vanilla container has no rate of its own and stays limited by its slots.

carriers are the pipe and the junction. a pipe is a length of tube: it carries along the axis it was laid on, in at one end and out at the other, either way round. direction comes from where the items entered rather than from the block, so a run reads straight off the world with nothing to configure and no way to build one backwards. turning a corner is what the junction is for.

**an uncapped pipe spills.** a pipe end with nothing in front of it drops its contents into the world, which is what makes capping a run matter — any block at all is a cap, a junction included. spilling is the last resort and never a race: the whole network is searched for somewhere that will actually take the items first, so an open end on one branch of a junction never beats a chest on the other, and a chest that has merely filled up makes items wait rather than hit the floor. that lives on `AnionItemPipeBlock.spillAt` rather than in the router, so gas venting or a conduit shorting out is the same hook and none of them inherit each other's rules.

drop-offs are a bus port or any vanilla container on an open side. so both of these work, and neither needs a machine anywhere on the run:

```
chest -> crafting table -> pipe -> chest
chest -> crafting table -> chute -> bus port -> buffer
```

**discovery** is a cell index in the `transport` column family, keyed by world+chunk, paged in and out with its chunk. the database rather than chunk PDC because PDC access is main-thread-only Bukkit, and the graph work wants to go async later. entries are a hint: a cell that no longer holds a component drops out the next time it is read, so WorldEdit and other event-bypassing edits degrade instead of rotting. cells added without an event stay invisible until something re-places them — an admin rescan is the escape hatch.

how far async can go, when the graph lands: loading the index and resolving routes is pure memory and can move off-thread. vanilla Inventory access and world block reads cannot. so the graph work goes async and the actual item handoff stays sync, the same snapshot-then-revalidate discipline the starship slowTick uses.

blocks: `ITEM_PIPE` (three axes), `ITEM_PIPE_JUNCTION`, `ITEM_CHUTE`. an `AnionPillarBlock` claims one note per **axis**, not per facing, and every one of them resolves back to the same AnionBlock, so a BlockSet accepting a pipe accepts it in any orientation. the resource pack reuses a single Y-standing model and spins it in the blockstate, so an axis costs a note and nothing else.

axis rather than facing because a pillar has two ends and no front. six one-way facings could only ever say the same thing twice, and the two halves of each pair were indistinguishable in world — a north pipe and a south pipe rendered identically, so a correct debug report looked like a lie. bidirectional flow also folded the separate vertical pipe block into the Y axis.

placement: the axis runs out of the face you clicked, exactly as vanilla logs do. clicking the end of a run extends it and clicking its side branches off it, which is one rule instead of the old pile of aim heuristics.

-# note budget: the ZOMBIE instrument is at 18 of 25, with 13-15 free inside that range. dropping the chute's five extra facings gave 18-24 back; collapsing the pipes to axes gave 13-15.

---
RECIPE RAMBLING

time to ramble
recipe system!
needs to be solid, support custom recipes, processing times, and vanilla compat

so i need an api to
- register vanilla crafting table recipe
- register vanilla furnace recipe (for both furnace fuels and custom furnace burn times for certain AnionItems)
- register generic recipes that take varied amount of custom resources

like a run() recipe takes 10E every tick, and a process() recipe takes 100E every tick
so it would benefit people to actually switch off machines

so really i would need a generic api with adapters for each context
boils down to:
- ingredients (any resource/resources; a plain vanilla item is an `ItemKey`, which is just an amount-1 ItemStack used as an identity)
- time (in ticks)
- result (each function takes a different type, so you'd have ItemResult, GasResult, EnergyResult, CompoundResult (builder, can return more than one result with more than one type (used in machines)), FluidResult, etc)

result should have ingredient predicates that fill up as the items are provided, so a starved machine can still partially complete an operation (e.g. machines does not take 3kE and THEN smelt, but it takes 3kE WHILE smelting and as it gets the E it feeds it to the progress for that ingredient... same with gas, items, anything)
HOWEVER! this is still limited proportionately to the needed amount of resource based on the recipe processing time AGAINST/VS the machine inputs!

example:
machine has ten inputs with 10R/pTick per input.
MACHINES HAVE A SEPARATE CONFIG, ALONG WITH INTERNAL BUFFERS THAT GET PULLED FROM AS FAST AS THE RECIPE CALLS FOR THINGS

Recipe has two ingredients:
1. `18R/pTick Oxygen for 1800R`
2. `5R/pTick  Iron   for 500R`
   Processing Time: 10o/pTick (o = operations)
   Output: `1 Steel Ingot`

machine is starved if only one input is used for iron and one input used for oxygen
it REQUIRES usage of multiple ports. (two oxygen inputs = 20R/pTick, feeds 18R/pTick demand)

again, this will all be powered by a GENERIC system, we just need a recipe system that can be implemented by specific classes, just like AnionItem and AnionBlock work!
so then there will be RecipeTypes that have adapters for the recipe subsystem. ShapedCraftingTableAdapter handles shaped crafting recipes, FurnaceSmeltAdapter handles furnace items to smelt, while FurnaceFuelAdapter handles custom fuel burning types (not natively supported, implement using listeners)
Machines will also have their own unique recipe adapters that interface with the recipe subsystem.

WHILE WORKING ON THIS, KEEP THE STRUCTURE OF THE ANIONBLOCK AND ANIONITEM CLASSES IN MIND. THE API FOR THOSE SHOULD BE AS IDENTICAL AS POSSIBLE.


---
MACHINES
---

**Structure Checks**

Machines in both TERF and Ion both have physical anchors that the machine structure is checked against.
- TERF uses relative offsets that respect the rotation of the Machine Core
- Ion uses relative offsets that respect the rotation of the Multiblock's Sign. (Thrusters are a special case, as they are detected upon starship detection, not initialized normally.)

Notice how both systems use an absolute position in the world with a rotation-aware object- that's not something that's easy to circumvent.
Ideally, Anion would move away from relative checks for basic machines and just rely upon one-time detection with world listener events-
But that brings about it's own set of issues, because every time a block is modified in the world, we have to floodfill the surrounding blocks to see if they exist in a machine structure,
build a map of the found blocks, and compare them against all registered structure sets.

However-
Anion uses RocksDB, a database that *should* be fast enough to handle starship movements and Machine updates concurrently with minimal overhead.
So instead of using an anchor point in the world with a relative block set we compare against, we can have:

```
BlockSet [Class]
| - StructureSet [Internal Var] (Mapped collection of blocks to Vec3i local offsets in relation to the BlockSet Origin)
| - Origin       [Internal Var] (Origin point (where the BlockSet anchor/core is placed) that StructureSet is compared against)
| - Offset       [Constructor]  (When calling the class (and not the builder functions in the companion object), we can provide an offset to the Origin of the BlockSet)
\ - Rotation     [Constructor]  (Now, apply a rotation to the BlockSet checks proportionate to the required Rotation to match the StructureSet to the offset Origin.)
```

BlockSet ia an (objectively) more ergonomic solution to handle the Machine structure check issues. BlockSet also ensures simplicity, because it simply checks if all configured blocks are present in the structure. It does not *care* about ports, or displays, or any other Machine-specific component.

-# Cells are matched by block *identity*, not blockstate.
A BlockSet cell holds a list of BlockMatchers rather than a list of BlockStates. An AnionBlock matcher compares the note block's instrument+note pair; a vanilla matcher compares material only. Incidental state (`powered`, `waterlogged`, ...) is never compared, so a redstone signal or a bucket of water can't break a machine apart.

-# To provide more context on how StructureSet functions:
StructureSet consists of `slices()`  of assignments of character mappings. For a publicly available example, take a look at Bukkit's ShapedRecipe class, specifically how it uses a builder to construct a recipe, then uses checks in the recipe "shape" iterator to ensure the recipe is valid before registering it.
Outside of the `slice()` function, the class contains:
- an `anchor()` function, which takes a character and an Anion or Vanilla (Bukkit) Block and adds it to a mapping list
- an `assign()` function, that also takes a character and an Anion or Vanilla (Bukkit) Block (This differs from `anchor()` in that multiple assignments can be made to one character to allow for alternate blocks to exist under that same representation. `anchor()` only allows one assignment and one representation in the StructureSet.)

-# `anchor()` shipped as `core()`, and it is one call per structure — but the block it names does *not* have to be unique.
Pass a block that fills the structure (the casing, say) and the first cell that char lands on becomes the origin, so a machine only needs a dedicated core block when you actually want one. Slices are walked in a fixed order, so that choice is stable across rebuilds.
The tradeoff is in assembly: `candidatesAt` cheap-rejects on the origin cell, and a dedicated core block kills nearly every candidate there. A repeated core makes that filter weaker, so a very large coreless structure does more full resolves per wrench click, and two identical ones built flush together are likelier to read as ambiguous.

Past the given schema, this is all that BlockSet should contain, as it's only meant to be a convenient and ergonomic way of checking structures.

*The last design hurdle for Machine structure sets is a fast iterator to match the surrounding blocks in every rotation and offset for a given structure to it's registered counterparts*

-# Solved: `Machine.candidatesAt(level, clickedPosition)`.
The clicked block is the anchor, not the core, so a player can wrench any face of the thing they built. For each registered type it first filters the BlockSet down to the cells the clicked block could possibly be filling (pure in-memory, rotation independent), then for each rotation × candidate cell derives the implied origin and rejects it on a single world read of the core cell before ever attempting a full structure resolve. Rotationally symmetric structures matching at the same origin under several rotations collapse to one candidate. Anything already assembled at that origin is skipped so re-wrenching can't duplicate a machine.
Ambiguity is a hard error, not a coin flip: if two distinct structures both fully match, nothing assembles and the player is told to break one apart.

---

MACHINES PT.2

Machine Inventories/Buffers

Machines will have a set of buffers that can be added to a map of buffers, present in the (implemented) abstract Machine class.
These buffers are keyed with strings, and automatically bind to ports placed in the "casing" blocks on the Machine.
(A NamespacedKey is just a wrapper around a string, so "string key" and "NamespacedKey" mean the same thing here — buffers use plain strings, resources use NamespacedKeys because they're registry entries.)
Buffers are given a ResourceType when assigned in the Machine definition, but can take any Resource within the assigned ResourceType, given that it is one at a time.
On the off-chance that there are two buffers of the same resource type (e.g. two item inputs for a steel mill that operates without gas), the player will be able to cycle the buffer that the given port assigns to.
Buffers will have two caps for item transfer- a softCap and a hardCap. softCap is the capacity that is given to each added port on the machine (e.g. 20Ep/Port), while hardCap is the limit for all additive ports (preventing someone from making a powerbank completely out of energy ports to instantly discharge it, for example.).

Buffers, like every other Anion component, need to have a generic class that specific implementations can extend. As an example, most Machine buffers are just a simple key that exists purely in data- but some machines may contain vanilla inventories (of any shape) that must be treated as Buffers by the Machine as well! Entity buffers (reliance on an entity to be present to deposit or extract from), Vanilla Inventory buffers are two specific implementations that would benefit from a generic class that they can extend.

Machine Ports/IO

Machines in Anion will be fairly unique in their handling of dynamic Port assignments.
As mentioned under the Structure Checks section, BlockSets can contain multiple potential assignments for the same character.
The primary purpose of that specification is to allow for the dynamic assignment of Ports at Machine assembly time.

-# Ports are frozen at assembly.
Assembly records which variant actually filled every cell (`Machine.resolvedStructure`) and that map is what every later structure check compares against — not the original list of acceptable variants. So swapping a casing block for a bus block on a running machine *breaks* the machine instead of silently gaining it a port; the player has to re-wrench. That frozen map is also what port discovery filters over, so ports never need a second pass over the world, and it's serialized, so a machine comes back off disk with exactly the port layout it was built with.
Structure cells may be shared between overlapping machines. Port cells may not — assembly refuses if a port cell already belongs to another machine.
As seen in the Machine Inventories/Buffers section, ports have softCaps and hardCaps. Those caps come into play here,  because players can place as many ports as they would like in Machine BlockSet slices that allow it.

The purposes of Ports is as follows:
linking to a Machine Buffer, linking to internal systems (Machine Database/Logic Integration), or providing a status readout (for Machine Display blocks- status readouts are text displays shrunk down to fit onto a singe block).
Ports will take no part in any future Transport System movement loop, as they only exist to provide access to internal Buffers and Machine readouts.
Transport Systems will have their own adapter blocks that attach on the outside of MachinePorts, but that's for another section.

Machine Processing

Anion will accomplish Machine processing/results through it's relatively robust recipe backend.
Machines, when idle, will consume a fixed amount of power via a recipe- giving players a reason to make logic-based switches and machine power breakers. (This is assuming that the Machine has a power requirement! Some machines do nothing when idle, like Steam Turbines!)
Simply put: Machines will be able to simultaneously execute multiple recipes at once from the stored Resources in Machine Buffers.
diena1 — 7/24/26, 2:24 PM
Machine Class Layout

Machine is an abstract class that more targeted implementations will extend. For example, in Minecraft, the Bucket item has several inheritors, like the BucketEntityContainerItem (going off of memory). So, in Anion, Machine can extend off into SimpleMachine (static structure with port substitutes), TankMachine (custom floodfill logic for tanks, this class gets extended into GasTankMachine or FluidTankMachine), or ComplexMachine (either hybrid detection or really complex tick loops would use this)

-# As built, those are `PortedMachine` (static BlockSet, ports resolved off the frozen structure) and `PortedTankMachine` (no BlockSet, owns its own flood fill — still TODO). ComplexMachine hasn't been needed yet; a machine with a weird tick loop just extends Machine directly.

we need:

detection
a way to make sure the machine is intact
a way to check the structure (if it tiles, for example, we need to easily check the tiling slices) (WE HAVE THAT! BlockSet is a structure checker and supports alternate subtitutes/multi-defintions for one char)
a way to floodfill the structure if it's a procedural multiblock (like a tank) (delegate structure checks and machine state to individual machines? no, better to have something extend Machine and add that logic)

-# The intact check ended up split in two, because one of them runs constantly and the other allocates:
- `isIntact(): Boolean` — fail fast, allocation free. This is what gates the tick loop.
- `structureResult(): StructureResult` — also reports which cells are wrong. Drives tank draining and break particles through the `onStructureChanged(result)` hook.

Structure is NOT re-checked on a timer. `MachineIndex` maps world cell -> machines occupying it, block break/place/explode events queue only the machines that actually own the changed cell, and the queue is drained once a second. Checking every machine's every block every slowTick doesn't survive contact with a few hundred machines.
A broken machine stays assembled and goes inactive; it starts working again the moment the exact block it was assembled with is put back. Nothing regenerates itself. Past 50% of cells mismatched it gives up and disassembles — buffers spill (items drop, everything else voids) and the save is deleted.
Carried machines are deliberately absent from MachineIndex: their cells move every tick, and the carrier already revalidates them once a move settles.

storage/buffers
generic class that can be extended by different buffer interfaces, provides basic get/set methods
implementations of the class (ChestBufferBridge, FurnaceBufferBridge, MachineBuffer) implement the get/set methods and provide information on what they can store
storage/buffers' storage layout is still TBD (namespaced key serialization for AnionResources? AnionItem has a vanilla item wrapper, so that could work? or providing a direct translation layer in the get function could work too)
side note, the vanilla buffers would be used for certain machines, like a StorageAccessorTerminalMachine for a ItemSiloMachine (names not final)

runtime
machine tick loop
passive ticking recipe- can be put in the slowTick runtime or primary tick runtime, TBD
tick loop will handle scanning buffers
tick loop will not care about I/O for machine ports because ports are literally just an accessor to the internal buffer- transfer rules will be handled by the transport subsystem

data transmittance
~~a way to assign channels to variables in the machine (give a variable an ID, like a dataMap(namespacedKey to DataChannel), then DataChannel can have different possible modes, like storing a number, a binary toggle, a string map (inventories), etc etc)~~
~~a way to interact with those (get/set methods in DataChannel or generic in Machine, TBD)~~
data transmittance will not care about linked machines, it is, just like ports, an interface
the mainframe multiblock will handle every single intermediary connection between machines

-# Scrapped the channel map. A machine doesn't hang a map of named channels off itself and let things read them — it *emits a signed signal down a line*, and whatever picks that signal up decides what to do with it. A mainframe grabs the signal and reroutes it based on the config stored for that signature. Same reasoning as ports: the machine exposes, it does not route.
Not implemented yet, so the Machine layout below has no data field at all.
```
Machine
|- displayName: String
|- blockSet: BlockSet?      // null for machines that own their own isIntact()
|- namespacedKey: NamespacedKey = adaptedFromDisplayNameVariable
|- resolvedStructure: Map<Vec3i, BlockMatcher>  // frozen at assembly, serialized
|- buffers: Map<String, MachineBuffer>
| [FUNCTIONS]
|- tick()
|- slowTick()
|- isIntact(): Boolean                  // fail fast, gates the tick loop
|- structureResult(): StructureResult   // intact + which cells are broken
|- onStructureChanged(result)           // drain, particles, whatever the machine wants
```

--- 

