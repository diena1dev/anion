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

BasicMachine
a machine made up of essential and casing blocks.

casing blocks: set of blocks that can be interchangeably used for their assigned part in the machine structure.
as an example, the current codebase uses copper machine casings and casing variants. in gameplay, the casing variants will cost more than basic casing.
casing variants with i/o ports (energy, fluid, item, gas) will have soft and hard caps for i/o limits. soft caps limit how much a SINGLE port can input or output, while hard caps limit how much ALL COMBINED ports can input or output. this is to allow emergent engineering through the introduction of recipes that take more than a single machine port can handle, causing the player to need to install more machine ports to completely feed the machine's demand.

essential blocks: required blocks for the machine to function, and must match the definition of the block exactly.
as an example: an assembly matrix at the center of an assembler machine, or arc rods in an arc furnace. used as a complimentary feature with casing blocks, as it can: ensure a fixed cost for machines, make detection of unique machines easier, and force specific block palettes in machine structures.

recipe handling: NYI

---

ProceduralMachine
a machine with procedural structure checks.

ProceduralMachines have the same base "casing" and "essential" blocks that BasicMachines have, but with the added ability to procedural detect added machine components.

a few examples:
a tileable battery array that adds to the capacity of the machine based on the amount of batteries connected in series to a side of the machine.
a long, diagonally tiling set of blocks that starts from the machine base and extends to a hardcoded structure.
a line of blocks that doesn't follow a set pattern, but must originate at the base of the machine and end at another part of the procedural machines without having any branches in it's path to those points.
a fluid tank made up of any shape as long as it is a closed shape and under a set size limit. if the tank is broken while the machine has fluid inside of it, the fluid is lost.

---

CompoundMachine
a machine made up of BasicMachines and ProceduralMachines

DISCUSS:
a mainframe machine is planned. some machines have a dataline machine port on them, which allows players to connect the machines to a central mainframe for statistic monitoring, control panel linking (control panels are seperate machines from the machines they control), and a "compute" resource (an ingredient for certain recipes).
a CompoundMachine would do the work of this mainframe, making it mostly useless, and allowing the ProceduralMachine to replace it. provide input on if it would be wise to drop the idea of CompoundMachine in favor of a ProceduralMachine

the one use case for a CompoundMachine would be for a modular nuclear reactor with multiple subsections,
but i imagine ProcedualMachine could do that too