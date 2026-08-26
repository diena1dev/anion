# dclang v0.3 — values

**Status: built.** Kept as the record of what was decided and why. Companion to `dcprgm_plan.md`;
`dcprgm_readme.md` is still the developer-owned spec and this file does not edit it.

**One sentence:** a dclang value stops being a number and becomes a number, *text*, a position or a list,
machines can be handed one instead of a boolean, and the language gains operators to ask questions
about them.

`Str` was added to the original scope on the same reasoning as `Vec`: a display wants labels, and a type
added later costs a second migration of `DcValue`, `DcType` and every operator's rules.

**Not in v0.3:** loops, local variables, statement blocks, procedural execution, cross-machine input
reads. Those are v0.4 and they need an execution model, not a value type. §7 says why each is out, and
`dcprgm_v0.4_spec.md` says what they become.

---

## 1. Why this and not iteration

The driving cases are a radar feeding a display, and factory routing. Neither needs control flow.

A radar knows its own position and can filter in Kotlin. A display knows how to draw a text graph and can
loop in Kotlin. What neither can do today is **hand the other a value** — `DcProgrammable.invoke` takes a
`Boolean`, so the most any machine can be told is on/off. That is the actual ceiling, and it is a type
problem, not a language-power problem.

So: widen the value, widen `invoke`, add operators to interrogate lists. The heavy work stays in machines,
which is where it belongs — dclang is the patch cable.

---

## 2. The value type

`DcValue` stops being `@JvmInline value class DcValue(val number: Double)` and becomes a sealed interface.

```kotlin
sealed interface DcValue {

	/** whether this reads as true to `and`, `or`, `not` and `mode` */
	val truthy: Boolean

	data class Num(val number: Double) : DcValue {
		override val truthy: Boolean get() = number != 0.0
	}

	data class Str(val text: String) : DcValue {
		override val truthy: Boolean get() = text.isNotEmpty()
	}

	data class Lst(val items: List<DcValue>) : DcValue {
		override val truthy: Boolean get() = items.isNotEmpty()
	}

	data class Vec(val x: Double, val y: Double, val z: Double) : DcValue {
		override val truthy: Boolean get() = true
	}

	companion object {

		val TRUE = Num(1.0)
		val FALSE = Num(0.0)

		fun of(value: Boolean): DcValue = if (value) TRUE else FALSE

	}

}
```

**Cost of this change:** the inline class goes away, so every evaluation allocates. Today a tick's worth of
flight-control evaluation allocates nothing. Mitigate by interning `TRUE`/`FALSE` (above) and by having
machines push list values **on change, not per tick** — a radar sweeping every 20 ticks pushes 20× less
than one reporting every tick.

### 2.1 DECIDED: `Vec` is its own variant

Starship automation will want positions as a first-class thing, so `Vec` is a type rather than a
three-element `Lst`. `Vec3i` is already the vocabulary of the codebase, and flattening a position into a
list throws away the one fact every future operator on it needs.

v0.3 does no arithmetic on positions (§7.2), so the surface area it adds now is small: truthiness, and
degrading sensibly under the aggregates.

### 2.2 DECIDED: a `Vec` is always truthy

The alternatives — non-zero magnitude, or "is not the origin" — both make `not [some_position]` mean
something surprising, and a position at the world origin is not "false". A value that exists is true.

---

## 3. Literals

Numbers are written bracketed, like everything else:

```dclang
set [currnt_chrg] > [80] to [trbo_lsr_1:fire] mode [hold]
```

**The ambiguity:** `[5000]` could be an input named `5000`. Machine names are generated as
`${type}_${index}` so they always contain letters, but **group names are user-chosen** and nothing
currently stops `def_group [5000]`.

**DECIDED:** a bracketed word that parses as a number is a number literal, and `def_group` **rejects** a
name that parses as a number with a compile error (`group names may not be numbers`). This keeps the
readme's rule that all identifiers are bracketed, which is worth more than the edge case costs.

Rejected: unbracketed numbers (breaks the readme's one syntactic invariant); a sigil like `[#5000]` (ugly,
and players will forget it).

The `def_group` check belongs next to the existing "is a machine, so it cannot also be a group" error in
`DcParser.parseGroups`, and applies to group names only — machine names are generated as
`${type}_${index}` and always contain letters.

Text literals are **quoted inside the brackets**: `["ready"]`. Words are split on whitespace before
anything is parsed, so a text literal cannot contain a space. Good enough for labels and status names,
which is what they are for.

No list or position literals in v0.3. Both only ever come from a machine.

---

## 4. Operators

All of these are entries in the existing `DcOperators` table. No parser changes beyond the table.

Precedence, loosest to tightest. v0.2 shipped 1 and 2.

| precedence | operators | kind |
| --- | --- | --- |
| 1 | `or` | infix |
| 2 | `and` | infix |
| 3 | `=` `!=` `>` `<` `>=` `<=` | infix |
| 4 | `+` `-` | infix |
| 5 | `*` `/` | infix |
| — | `not` `count` `any` `all` `text` | prefix |

`=` and `!=` work across every type, and compare false between two different ones. The ordering
comparisons are numbers only.

`+` joins two numbers or two strings. Mixing them is a **type error, not a coercion** — `[count] + [label]`
is always a mistake and never a request. `text [x]` is the explicit way across, and the only way to get a
number or a position into a string.

Prefix operators bind tighter than every infix, as in v0.2. Parentheses group.

### 4.1 Comparison

Numeric, both sides `Num`, result `TRUE`/`FALSE`.

```dclang
set [currnt_throttle] >= [0.9] to [engine_1:overheat_warn] mode [hold]
set count [radar_1_contacts] != [0] to [alarm_1:sound] mode [hold]
```

A comparison against a `Lst` or `Vec` is **never silently false**. It is caught at compile time where the
type is knowable, and crashes the mainframe where it is not. See §4.4.

### 4.2 Arithmetic

`Num` only, apart from `+` on two strings. Division by zero yields `Num(0.0)` — deterministic and
unremarkable, which is what a scripting language in a game wants. Not a trip: an overrun is about compute,
not about arithmetic.

```dclang
set ([currnt_chrg] / [max_chrg]) > [0.5] to [trbo_lsr_1:ready_light] mode [hold]
```

### 4.3 Aggregates

Prefix, operand is a `Lst`.

| operator | on `Lst` | on anything else |
| --- | --- | --- |
| `count` | element count | `1` |
| `any` | true if any element is truthy | the value's own truthiness |
| `all` | true if every element is truthy (empty ⇒ true) | the value's own truthiness |
| `text` | its rendered form | its rendered form |

Degrading gracefully on a non-list is deliberate: a machine that changes an input from a scalar to a list
between versions should not break every program that read it.

```dclang
set count [radar_1_contacts] > [3] to [defence:go_loud] mode [toggle]
set not any [reactor_1_faults] to [drive_1:enable] mode [hold]
```

### 4.4 DECIDED: type errors are a compile error, or a crash

Order of preference, and the implementation follows it in this order:

**1. Compile error, wherever the type is knowable.** This requires machines to declare what their inputs
*are*, which they currently do not — `DcProgrammable.dataInputs` is a `List<String>` of bare names. Give
inputs a declared type:

```kotlin
enum class DcType { NUM, LST, VEC }

interface DcProgrammable {

	/** Values this machine emits, and what shape each one is. */
	val dataInputs: Map<String, DcType> get() = emptyMap()

}
```

The compiler then rejects `[contacts] > [5]` at save time with a real line number, which is where a type
error belongs. This is the single highest-value addition in v0.3 and it is what makes preference 1 the
common case rather than the rare one.

Cost: `dataInputs` changes shape, so every `DcProgrammable` and the editor's `available inputs:` header
move with it. Small — `ControlSeatMachine` is the only implementor today.

**2. Crash the mainframe, where it is not.** An offline machine's inputs go unchecked (existing rule, and
it has to stay — a program must survive its machine being unplugged), so a mismatch can still reach the
runtime. When it does, the mainframe crashes rather than evaluating to false: a silent false is a weapon
that never fires and a pilot who never learns why.

A crash is a harder stop than the compute trip in §6.2. A compute overrun is a *load* problem that clears
itself after a reboot; a type error is a *program* problem that will recur every time the same binding
evaluates, so rebooting into the same program just crashes again.

> **TODO: mainframe crash semantics are not specified here.** The developer will define how a crash differs
> from a compute trip, what it takes to clear one, and whether the offending program is quarantined on the
> way down. Preference 2 is therefore **not built**: preference 1 is, and the runtime path throws a
> `DcTypeError` that `DcRuntime.input` catches, warns once per expression, and skips the binding. The
> `TODO` is at that catch site.

Whatever the crash does, the release-everything rule from §6.2 still applies on the way out. A stuck
throttle is not an acceptable outcome of any failure mode.

---

## 5. `DcProgrammable.invoke`

```kotlin
interface DcProgrammable {

	/** Runs [function] with the value that drove it. */
	fun invoke(function: String, value: DcValue) = invoke(function, value.truthy)

	@Deprecated("implement invoke(String, DcValue)")
	fun invoke(function: String, active: Boolean) {}

}
```

Every machine that exists today keeps working untouched: the new default delegates to the old method. A
machine that wants the value overrides the new one. No migration commit needed.

Machines also gain a value-typed push, which `DcRuntime` already has as an overload:

```kotlin
mainframe.runtime.input("radar_1", "contacts", DcValue.Lst(contacts.map { it.toVec() }))
```

---

## 6. Runtime and budget

### 6.1 Charging list work

`DcExpr.cost` stays a **static estimate** used for the compile-time warning. It cannot know a list's
length, so aggregates charge their **actual** element count at evaluation — the same split already used for
group fan-out, which charges `membersOf(target).size` at dispatch rather than at compile.

This needs the evaluator to reach the budget, which it currently cannot: `DcExpr.evaluate(valueOf)` takes
only a lookup. Replace it with a context:

```kotlin
class DcEval(private val values: Map<String, DcValue>, private val budget: DcBudget) {

	fun valueOf(input: String): DcValue = values[input] ?: DcValue.FALSE

	/** charges [operations]; throws [DcOverrun] when the allowance is gone */
	fun charge(operations: Int)

}
```

`DcOverrun` is a private control-flow exception caught in `DcRuntime.input()`, which then trips exactly as
it does today. Throwing is the right tool here — an aggregate can be nested arbitrarily deep, and
threading a `Boolean` back up through `evaluate` would put a failure check in every operator lambda. **It
must never escape `DcRuntime`**, which is the whole reason it is private to this package.

### 6.2 Trip behaviour is unchanged

Overrun still releases everything delivering, clears latches and input cache, and reboots. Lists make
overruns *more* likely, which is the point — a radar with 400 contacts and three `count` bindings is a real
compute load and should be a real ship-fitting decision.

### 6.3 Compile-time estimate

`count [x]` cannot be costed statically. **DECIDED: aggregates get a nominal cost of 8**, so a program with
fifty of them warns at save time, and the runtime meter catches the rest. The warning is advisory anyway.

```kotlin
// TODO: tune. 8 is a guess — if aggregates turn out too cheap to warn on a program that trips in
//       flight, raise it. Read real numbers off `/machine debug` peak spend first.
private const val AGGREGATE_COST = 8
```

---

## 7. Explicitly out of scope

### 7.1 Element-wise filtering

No `[contacts] within [5000]`, no predicates, no lambdas. A per-element predicate is an anonymous function,
and that is the feature that turns an expression language into a real one.

**Do it in the machine instead.** A radar that knows its own position exposes several inputs:

```
available inputs: [contacts|contacts_near|threats|nearest_range]
```

The machine filters in Kotlin, where it is fast and untaxed, and dclang asks `count [threats] > [3]`. This
is strictly better than a language-level filter: the radar can use a spatial index, and the player gets
names that mean something instead of assembling a predicate.

Revisit in v0.4, where a procedural `for each` makes it natural.

### 7.2 Vector arithmetic

No `[pos_a] - [pos_b]`, no distance operator. Same reasoning: the machine that has two positions can
subtract them and expose `range` as a `Num`.

### 7.3 Cross-machine input reads

`set [radar_1:contacts] to …` inside another machine's file is **not** needed for any driving case, because
the radar's own `main.dcprgm` can bind its own input to a remote function:

```dclang
--- editing [main.dcprgm] for [radar_1] ---
set [contacts] to [display_1:plot] mode [hold]
```

Supporting it would mean a mainframe-wide reverse index of which programs read which machine's inputs, and
a rebuild of that index on every recompile. Real work, no payoff yet. v0.4.

### 7.4 Persistence of values

Runtime only, as now. A mainframe reboot starts every input at zero.

---

## 8. Worked examples

### 8.1 Radar to display

```dclang
--- editing [main.dcprgm] for [radar_1] ---
| available inputs: [contacts|threats|sweeping]
| available modes: [toggle|hold]

// the display is handed the whole contact list and draws it
set [contacts] to [tactical_display_1:plot] mode [hold]

// and a klaxon on anything hostile, with a threshold so one scout does not scramble the ship
set count [threats] > [2] to [alarm_1:sound] mode [hold]
```

`tactical_display_1` receives `DcValue.Lst` of `DcValue.Vec` and does its own plotting.

### 8.2 A weapon that respects charge

```dclang
--- editing [main.dcprgm] for [ctrl_seat_1] ---
| available inputs: [lc|rc|w|a|s|d|sp|sh|altlc|altrc]

set [altlc] to [primary_weapons:fire] mode [toggle]
```

```dclang
--- editing [main.dcprgm] for [trbo_lsr_1] ---
| available inputs: [currnt_chrg|max_chrg]

// the ready light is a pure readout, no pilot input involved
set ([currnt_chrg] / [max_chrg]) >= [0.8] to [trbo_lsr_1:ready_light] mode [hold]
```

### 8.3 Factory gating

```dclang
--- editing [main.dcprgm] for [asm_1] ---
| available inputs: [done|jammed|output_count]

// chain the line
set [done] and not [jammed] to [asm_2:start] mode [hold]

// stop feeding when the output buffer is backing up
set [output_count] > [32] to [feed_belt_1:halt] mode [hold]
```

Note what this cannot do: "make exactly 20 and stop". That is a counter, which is state, which is v0.4.
Everything up to that point is reachable without an execution model.

---

## 9. Work breakdown

| file | change |
| --- | --- |
| `DcExpr.kt` | `DcValue` becomes sealed; `evaluate` takes `DcEval`; nodes charge through it |
| `DcExpr.kt` | comparison, arithmetic and aggregate entries in `DcOperators` |
| `DcParser.kt` | number literals in `readUnary`; `def_group` rejects numeric names; type-check comparisons against declared input types |
| `DcProgrammable.kt` | `invoke(String, DcValue)` with a delegating default; `dataInputs` becomes `Map<String, DcType>` |
| `DcRuntime.kt` | build `DcEval` per evaluation, catch `DcOverrun` and trip, catch `DcTypeError` and TODO |
| `ControlSeatMachine.kt` | `dataInputs` moves to the typed form (all `NUM`) |
| `DcBudget.kt` | unchanged |
| `dcprgm_readme.md` | developer writes the spec text |

`DcStore` passes `dataInputs` to the parser and so moves with its type. No changes to `DcProgram`,
`MainframeMachine`, or the console.

---

## 10. Decisions

| # | question | decision |
| --- | --- | --- |
| 1 | `Vec` its own variant, or three-element `Lst`? | **Own variant** — starship automation will want positions as a type (§2.1) |
| 2 | Numeric group names | **Reject at compile** (§3) |
| 3 | Type mismatch: warn or fail? | **Compile error where knowable, crash where not** (§4.4) |
| 4 | Nominal aggregate cost | **8**, with a TODO to tune off real `/machine debug` numbers (§6.3) |

| 5 | `Str` as a type | **Added**, same reasoning as `Vec` (header) |

### Still open

- **Mainframe crash semantics** (§4.4). What a crash is, how it differs from a compute trip, what clears
  one, whether the offending program is quarantined. Not built; the runtime path stops at a caught
  `DcTypeError` with a `TODO` at the catch site in `DcRuntime.input`.
- **`AGGREGATE_COST = 8`** is a guess, with a `TODO` on it in `DcOperators`. Wants real `/machine debug`
  peak numbers off a flying ship.
- **Nothing emits a non-`NUM` input yet.** `Str`, `Vec` and `Lst` are carried end to end but untested
  against a real producer — the radar is the first one that will exercise them.
