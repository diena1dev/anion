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
