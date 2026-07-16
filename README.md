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
|   \- listeners
|   \- machine
\- data
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
- ingredients (any resource/resources, AnionVanillaItem can be used internally)
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

-# To provide more context on how StructureSet functions:
StructureSet consists of `slices()`  of assignments of character mappings. For a publicly available example, take a look at Bukkit's ShapedRecipe class, specifically how it uses a builder to construct a recipe, then uses checks in the recipe "shape" iterator to ensure the recipe is valid before registering it.
Outside of the `slice()` function, the class contains:
- an `anchor()` function, which takes a character and an Anion or Vanilla (Bukkit) Block and adds it to a mapping list
- an `assign()` function, that also takes a character and an Anion or Vanilla (Bukkit) Block (This differs from `anchor()` in that multiple assignments can be made to one character to allow for alternate blocks to exist under that same representation. `anchor()` only allows one assignment and one representation in the StructureSet.)

Past the given schema, this is all that BlockSet should contain, as it's only meant to be a convenient and ergonomic way of checking structures.

*The last design hurdle for Machine structure sets is a fast iterator to match the surrounding blocks in every rotation and offset for a given structure to it's registered counterparts*
