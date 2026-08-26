# dclang v0.4 — the procedural context

**Status: not started.** Design agreed, nothing built. Companion to `dcprgm_plan.md` and
`dcprgm_v0.3_spec.md`; `dcprgm_readme.md` is the developer-owned spec and this file does not edit it.

**One sentence:** dclang gains a second *execution context* — code that runs on a trigger, top to bottom,
with local variables and bounded loops — without becoming a second language.

Read `dcprgm_v0.3_spec.md` first. This builds directly on its value types and its budget.

---

## 1. The decision this whole file rests on

Factory automation and flight control want different things. Flight control is **reactive**: an input
changes, expressions re-evaluate, functions fire, all inside one tick, latency is the whole point. Factory
automation is **procedural**: do this, wait, check, do that, count to twenty, stop.

The tempting move is two languages — a simple one for ships, a real one for factories. **Do not do that.**

- Two parsers, two operator tables, two editors, two budgets, two sets of docs, drifting apart from the
  first commit.
- Players will immediately want them to talk. Radar spots a raider, factory starts building torpedoes.
  Cross-dialect calls are the thing that collapses two-language designs.
- A player would have to learn `and`/`or`/`not`/`count` twice.

**The split is real, but it is not syntax — it is *when code runs*.** So: one grammar, one `DcOperators`
table, one `DcValue`, one budget mechanism, **two statement sets**.

```dclang
// reactive context — main.dcprgm, exactly as it is today
set ([w] or [alta]) and not [sh] to [frwd_thrust:boost] mode [hold]

// procedural context — same file, new statement
on [asm_1:done] do
	let [made] = [made] + [1]
	if [made] < [20] then [asm_1:start]
end
```

Expressions inside both are the same expressions, parsed by the same `readExpr`.

---

## 2. What is actually new

| piece | why it does not exist yet |
| --- | --- |
| statements with a body | the grammar is one statement per line, no nesting |
| local variables | nothing in dclang has ever stored anything; latches live in the runtime, not the language |
| bounded iteration | there is no control flow at all |
| a trigger | reactive code is driven by input edges; procedural code needs a reason to start |
| a scheduler | a procedural run does not have to finish in one tick, and must not have to |

Everything else — values, operators, type checking, groups, dispatch, the budget — is reused unchanged.

---

## 3. Statements

Statement kinds get **their own table**, the same way operators do. This is the extension point; a new
statement is one entry, not a new branch in the parser.

```kotlin
object DcStatements {

	class Kind(
		val keyword: String,
		/** whether this statement opens a body that `end` closes */
		val block: Boolean,
		val parse: (words: List<String>, line: Int) -> DcStatement?,
	)

}
```

### 3.1 Proposed set

```
on <expression> do ... end      // trigger: run the body when the expression goes true
let [name] = <expression>       // assign a local
if <expression> then <call>     // one-line conditional
if <expression> do ... end      // block conditional
for each [name] in <expression> do ... end
call [target:function]          // fire a function without binding an input to it
wait [ticks]                    // yield until later, see §5
```

`end` closes any block. Indentation is not significant — the readme's whole aesthetic is flat lines, and
whitespace-sensitivity in a sign-based editor would be miserable.

### 3.2 OPEN: is the procedural context in the same file?

Two options, and this needs a decision before implementation:

**(a) Same `main.dcprgm`**, `set` and `on` side by side. Simpler for the player, one file per machine, and
the editor does not change. Risk: the two contexts are visually indistinguishable, and a player will
expect `set` inside an `on` body to work.

**(b) A separate file**, e.g. `routines.dcprgm` per machine. Unambiguous, and the editor can label it. Cost:
`DcStore` grows a second per-machine file, the console grows an entry, and save/compile paths double.

**Recommended: (a)**, with `set` a compile error inside a block. One file, one editor, and the error
message teaches the distinction better than two files would.

---

## 4. Locals, and the rule that keeps this safe

`let` binds a name inside the running routine. Locals live in the **routine's activation**, not on the
machine and not on the mainframe.

**Hard rule: no shared mutable state between contexts.** A `let` is never visible to a `set`, and never
visible to another routine.

The reason is not tidiness. Reactive bindings fire from whichever machine ticks first, and machines tick
in no defined order. The moment a `set` can read something a routine wrote, the behaviour of a ship
depends on tick order — which is exactly the nondeterminism the whole budget design (`dcprgm_plan.md` §6,
"operations never milliseconds") went out of its way to eliminate. A pilot cannot learn a rule that
changes run to run.

Anything a routine needs to publish goes out through a machine — `call [display_1:set_line]` — and comes
back in as that machine's declared input. Machines are the only shared state, and they are already
main-thread and already typed.

### 4.1 OPEN: persistence of locals

A counter that resets on chunk unload is useless for "make 20 and stop". A counter that persists forever
is a save-file leak. Options: routine-scoped only (simplest, and the counter dies with the run);
machine-scoped with explicit `remember [name]`; or push it back on the machine and make the assembler own
its own count. **Leaning: routine-scoped, and machines own durable counters** — but this needs the
developer, because it is a gameplay decision about what a factory forgets.

---

## 5. Execution model — the actual foundational change

A procedural run can be long. That breaks the v0.2/v0.3 budget, which assumes a whole program's cost fits
in one tick.

### 5.1 Two budget policies over one `DcBudget`

- **Reactive** keeps exactly what is built: a per-tick allowance, and an overrun **trips** the mainframe —
  releases everything delivering, reboots for `REBOOT_TICKS`. Flight controls must be bounded and
  immediate.
- **Procedural** gets a **per-run** cap and is **resumable**: execute N operations, save the program
  counter and locals, continue next tick. A 10,000-operation factory job takes 40 ticks and nobody cares,
  because a factory has no latency requirement.

### 5.2 Resumption, not threads

This is the correct answer to "should `DcRuntime` be async", and it is worth writing down because the
instinct to reach for threads here is strong and wrong:

- `deliver()` ends in `programmable.invoke(...)`, which mutates machines. Bukkit/NMS is main-thread only
  (`CLAUDE.md`, Threading).
- Going async would need the snapshot-then-revalidate dance for machines that disassembled mid-run.
- It buys nothing. The problem is not that the work is slow, it is that there is *a lot* of it.

Resumption solves the actual problem with none of that: same main thread, same `Tasks.runSync`, no
threading rules violated, and long computation stops being a problem instead of being relocated.

```kotlin
/** One in-flight routine: where it is, what it knows, and what it is allowed to spend. */
class DcRoutine(
	private val body: List<DcStatement>,
	private val locals: MutableMap<String, DcValue>,
) {

	/** Runs until the tick's slice is spent. Returns whether it still has work. */
	fun step(eval: DcEval, operations: Int): Boolean

}
```

The mainframe holds the live routines and steps them in its `tick()`, which is currently empty except for
the console redraw.

### 5.3 Bounded iteration only

`for each` over a group or a list is bounded by something the runtime can see. **`while` is refused** —
not because it is hard, but because an unbounded loop plus a resumable scheduler is a routine that never
finishes and never fails, which is worse than one that trips.

A routine that exceeds its per-run cap is **abandoned**, not resumed forever. Same release-everything rule
as a trip: whatever it was driving is let go.

### 5.4 OPEN: what a routine does when its machine goes away

Mid-run, the assembler it is driving gets broken. Abandon the routine? Pause it? Let it run and no-op its
calls (which is what `deliver` already does for offline machines)? **Leaning: let it run** — consistent
with "a broadcast does not care whether anyone is listening" — but confirm.

---

## 6. Triggers

`on <expression> do` reuses the reactive machinery entirely: the expression's `inputs` go in the same
`bindingsReading` index, and a rising edge starts the routine instead of delivering a function.

Consequences worth stating:

- A routine that is already running and gets triggered again does **not** start a second copy. It is one
  routine per `on` statement per machine. Re-trigger while running is ignored. (Alternative — queue it —
  is a footgun: a jammed assembler pulsing `done` would build an unbounded queue.)
- Triggers cost budget like any other expression evaluation, charged to the reactive per-tick allowance.
- `mode` does not apply to `on`. A trigger is always edge-driven, which is `push` semantics.

---

## 7. Cross-machine input reads

Deferred from v0.3 §7.3. `set [radar_1:contacts] to …` in a *different* machine's file.

Not required by anything yet, but v0.4 makes it more attractive: a factory routine wants to read several
machines. It needs a mainframe-wide reverse index — which programs read which machine's inputs — rebuilt
on every `recompile()`. `DcProgram.bindingsReading` becomes mainframe-scoped rather than program-scoped.

**Recommended: still defer.** Do it only if a real routine turns out to need it, and note that the same
effect is already reachable by having the emitting machine's own file `call` into the routine's machine.

---

## 8. Worked example

```dclang
--- editing [main.dcprgm] for [asm_1] ---
| dclang v0.4
|
| available inputs: [done:num | jammed:num | output_count:num]
| available functions: [start|halt]

// reactive, unchanged: chain the line
set [done] and not [jammed] to [asm_2:start] mode [push]

// procedural: build a batch of twenty, then stop and say so
on [batch_button:pressed] do

	let [made] = [0]

	for each [slot] in [input_buffer:contents] do

		if [made] >= [20] then [asm_1:halt]
		call [asm_1:start]
		let [made] = [made] + [1]

	end

	call [display_1:set_line] with ["batch_done"]

end
```

Note `mode [push]` on the reactive line: `done` pulsing while an assembler finishes should start the next
stage once, not every tick it stays true.

`call … with …` implies functions take an argument. That falls out of v0.3's
`invoke(function, value: DcValue)` at no extra cost, and `call` without `with` passes `TRUE`.

---

## 9. Work breakdown

| file | change |
| --- | --- |
| `DcStatement.kt` (new) | statement AST, `DcStatements` table |
| `DcRoutine.kt` (new) | activation record, `step()`, locals |
| `DcParser.kt` | block parsing, `end` matching, statement dispatch off the table |
| `DcProgram.kt` | a program is bindings **and** routines |
| `DcRuntime.kt` | trigger routines off rising edges; hold live routines |
| `DcBudget.kt` | a per-run allowance alongside the per-tick one |
| `MainframeMachine.kt` | `tick()` steps live routines |
| `dcprgm_readme.md` | developer writes the spec text |

The parser change is the one with real risk: the lexer is line-based (`statements()` returns one word list
per line) and blocks need to span lines. Either statements gain a nesting pass over the flat line list —
recommended, keeps `readExpr` untouched — or the lexer changes, which would touch every existing path.

---

## 10. Open questions for the developer

1. **Same file or a separate `routines.dcprgm`?** (§3.2 — recommends same file, `set` banned in blocks.)
2. **Do locals persist?** (§4.1 — leans routine-scoped, machines own durable counters. Gameplay call.)
3. **Routine whose machine disappears mid-run** (§5.4 — leans let it run, calls no-op.)
4. **Per-run operation cap** — a number, and whether it scales with mainframe compute like the per-tick
   budget does. Wants real `/machine debug` numbers first.
5. **Mainframe crash semantics** — still outstanding from v0.3 §4.4, and v0.4 needs it too: an abandoned
   routine and a crashed mainframe should probably not look the same to a player.
