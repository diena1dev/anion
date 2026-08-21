# Anion

Paper plugin (Kotlin, JVM 25, paperweight userdev, Paper API 26.2). Group `dev.diena`, package root `dev.diena.anion`, namespace `anion`.
Entry points: `Anion.kt` (`AnionBootstrap` → `Anion : JavaPlugin`). Commands are registered by the astralchroma brigadier KSP processor (`com.example.plugin.Registration`).

## Build

Do **not** run `./gradlew build` / `runServer` to verify changes — the developer builds and tests themselves. Write the change and stop.

## Architecture

### Bases are frameworks, not features

`AnionItem`, `AnionBlock`, `AnionRecipe`, `AnionRegistry`, `Starship` (and any future `AnionGas` / `AnionFluid` / `AnionEnergy`) are **base layers**. They define the shape of a thing and the hooks that specific behaviour plugs into. They must not contain behaviour for any one concrete item/block/machine/ship.

- Behaviour lives in a subclass (`AnionBlasterPistolItem`, `AnionSmeltableItem`, `AnionBlockItem`) or in a handler lambda passed to the base constructor (`interactHandler`, `placeHandler`, …).
- Concrete instances get declared and registered in the `Anion*s` object for that type (`AnionItems`, `AnionBlocks`, `AnionRecipes`), never inline elsewhere.
- Keep the public API of the base classes as close to identical across types as possible. `AnionItem` and `AnionBlock` are the reference shape: constructor with `displayName` + `namespacedKey` + optional handler lambdas, then `open fun on*()` hooks that default to invoking the handler.
- If you find yourself adding an `if (this is SomeSpecificThing)` or a name check inside a base class, that logic belongs in a subclass or a helper instead.

### Starship: carrier + helpers

`Starship` is a collection of blocks (`blockHashMap: Vec3i -> BlockState`) plus the small set of functions that mutate that collection: `move`, `rotate`, `addBlock`, `removeBlock`, `updateBlock`, `create`, `load`. That is all it should ever be.

Everything else is a **helper class** that takes the calling `Starship` as an argument, does the real work, and applies the result back onto that carrier:

| helper | responsibility |
| --- | --- |
| `StarshipMovement` | computes translated/rotated block, block-entity and entity placement; returns the new block map |
| `StarshipCollision` | decides whether a move or rotation is legal |
| `StarshipVelocity` | holds velocity + sub-block offset, drives `Starship.move()` |
| `StarshipSimulator` | gravity, drag, thrusters, mass |
| `StarshipHitbox` | bounding volume |
| `StarshipSplit` | flood-fill for disconnected sections |
| `StarshipPackets` | client-side packet work |

Rules:

- `tick()` and `slowTick()` on `Starship` are **dispatch only** — a short ordered list of helper calls. No logic in the body.
- New starship functionality gets a new `Starship*` helper (or a method on an existing one), not a new method on `Starship`.
- Helpers are `object` when stateless (`StarshipMovement`, `StarshipCollision`, `StarshipSplit`) and a per-ship class with a `private constructor()` + `companion object { fun new(starship: Starship) }` when they hold state (`StarshipVelocity`, `StarshipSimulator`, `StarshipHitbox`).
- A helper takes the carrier ship as a parameter or a back-reference field, and mutates it through the ship's own public functions where one exists.

### Transport: the block is the behaviour

`AnionTransportComponent` is an interface, not a base class — a pipe is already an `AnionPillarBlock` and Kotlin gives you one superclass. A block that transport can use implements it and answers three questions: `exitsFor` (where something entering here can go), `drive` (what work this component does, nothing for a plain carrier), `describe` (its line in `/transport debug`).

- `AnionTransport.tick()` is **dispatch only** — resolve the component through `AnionTransportComponents.at(block)`, hand it a `TransportPass`, call `drive`. No block is ever named there, the same way `AnionItemDispatcher` never names an item.
- A driver gets a `TransportPass`, never the world, so the per-buffer rationing in `route()` cannot be skipped.
- Vanilla blocks cannot implement the interface, so they are adapted in `AnionTransportComponents.byMaterial` — one entry, not a branch in the pass.
- New component (gas pipe, pump, connector) = a new class implementing the interface + one line in `AnionBlocks`. If you are editing `AnionTransport` to add one, it is in the wrong place.

### Registries

`AnionRegistry<V>` + `AnionRegistryKey` + the `AnionRegistries` object. Registration throws on duplicate keys. Feature `object`s (`AnionItems`, `AnionBlocks`, `AnionRecipes`) are touched in `Anion.onEnable()` purely to force their static init to run the registrations.

### Threading

Bukkit/Paper and NMS world access is **main thread only**. Use `Tasks.runSync {}` / `Tasks.runAsync {}` / `Tasks.scheduleAsync(...)`; never `Thread` or a raw executor.

- The starship slowTick loop runs async and hops back to sync per ship.
- Anything async that reads ship state must snapshot on the main thread first, and re-validate against live state before applying its result back.
- `Starship.writingToWorld {}` wraps world writes so ships ignore the block events their own movement fires. Wrap any new code that stamps blocks into the world.

## Comments

Comments say **what** the code does, not why it was written that way. Keep them short.

- A doc comment on a function is one or two lines: what it does, what it returns. Not the failure mode it prevents, not the history behind it, not what would break without it.
- Don't write a paragraph justifying a guard clause. `if (applyingWorldChanges) return false // a ship's own world writes must never grow another ship` is the right size.
- One- and two-line comments are fine as-is; don't expand them.
- Side comments and jokes stay (`// go-go gadget internal item stack`, `// NO-OP atm`). They cost nothing and they're not the problem.
- `TODO:` / `FIXME:` blocks are task notes, not explanation — leave them long if they need to be, and keep them accurate.
- Prefer naming things well over explaining a bad name — see [Naming](#naming).
- Section banners (`///// MOVEMENT OPERATIONS (Moving, Rotating)`) are the existing convention in the larger classes — follow it in files that already use it.

## Naming

**Never use single-letter variable names.** Every name is at least one whole word that describes what the variable actually holds. This applies everywhere — locals, parameters, loop variables, lambda destructuring, `catch` bindings, generic-looking scratch values. There is no exemption for "it's just a loop counter" or "the scope is three lines".

- `for (b in blockPosSet)` → `for (blockPos in blockPosSet)`. `for (v in blockHashMap.keys)` → `for (localPosition in blockHashMap.keys)`. `catch (e: Throwable)` → `catch (exception: Throwable)`.
- Abbreviations that aren't words are the same problem: `ctx`, `evt`, `pos` alone, `bhm`, `tmp`, `n`. Write `context`, `event`, `blockPos`, `blockHashMap`, `scratchMap`, `count`.
- Domain acronyms established in Minecraft/NMS vocabulary are fine as part of a longer name: `nmsLevel`, `uuid`, `nbtTag`, `blockPos`. Ad-hoc contractions of ordinary words are not — `be` for block entity becomes `blockEntity`, so the map of block-entity block states is `blockEntityBlockMap`, not `beBlockMap`.
- Name the role, not the type. `starship`, `vectorToMoveIn`, `removedLocal`, `originAtSnapshot`, `detached` all say what the value is for. `vec3i`, `theSet`, `list2` do not.
- Kotlin's implicit `it` is acceptable in a one-expression lambda where the receiver is obvious (`values.firstOrNull { it.level == level }`). Name the parameter the moment the lambda spans more than one line or nests inside another lambda.
- Existing single-letter names in the codebase are legacy, not precedent. Rename them when you're already editing that function; don't open a separate renaming pass.

## Style

- **Indent with tabs, one tab per level, never spaces.** Tab width is 4. This is uniform across every `.kt` file — do not introduce space indentation, and do not reformat an existing file's indentation.
- Spaces are still correct for *alignment within a line* — trailing comment columns, KDoc ` * ` continuations. Only leading indentation is tabs.
- Multi-parameter functions are written with each parameter on its own line and a blank line after the opening paren, in the starship code. Follow the local style.
- `Vec3i` = block-space integer position, `Vec3` = fractional/velocity. Extensions for the conversions and operators live in `extensions/Paper.kt` (`plus`, `minus`, `div`, `blockPos`, `vec3i`, `floorVec3i`, `adjacentBlocks`, `rotateLeft`/`rotateRight`).
- NMS types (`ServerLevel`, `BlockState`, `BlockPos`) are used directly in starship internals; Bukkit types at the API/event boundary. Convert with `(world as CraftWorld).handle` / `(block as CraftBlock).blockState`.

## Design notes

Longer-form intent lives in `README.md` (machines, power, recipe system), `MACHINE_README.md`, and `src/main/kotlin/features/starship/STARSHIP_README.md`. Read the relevant one before designing in that area; keep them as the place for rambling design thinking, so it stays out of the code comments.
