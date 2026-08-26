package dev.diena.anion.features.scripting

import dev.diena.anion.Anion
import dev.diena.anion.features.machine.machine_types.scripting.mainframe.MainframeMachine
import org.bukkit.Bukkit

/**
 * Turns raw input states into function calls. One per mainframe, holding the mode state for every input
 * bound by one of its programs.
 *
 * An emitting machine reports what its inputs are doing every tick and nothing else — latching,
 * cooldowns and whether a call is even due all live here.
 */
class DcRuntime private constructor(private val mainframe: MainframeMachine) {

	companion object {

		fun new(mainframe: MainframeMachine): DcRuntime = DcRuntime(mainframe)

		/** ticks a toggle must wait before a second press may flip it back */
		const val TOGGLE_COOLDOWN = 5

	}

	/** mode state is per expression per mode: two bindings on one expression in one mode share a latch. */
	private data class StateKey(val machineName: String, val source: DcExpr, val mode: DcMode)

	private class InputState {

		var down = false
		var latched = false
		var lastFlipTick = 0
		var delivering = false

	}

	private val states: MutableMap<StateKey, InputState> = mutableMapOf()

	/** last reported value of every input, per machine. an expression needs the ones that did not change. */
	private val inputValues: MutableMap<String, MutableMap<String, DcValue>> = mutableMapOf()

	/** calls already reported as unimplemented, so a bad binding warns once instead of every tick */
	private val warned: MutableSet<String> = mutableSetOf()

	/** Reports what [input] on [machineName] is doing this tick, and delivers whatever that drives. */
	fun input(machineName: String, input: String, down: Boolean) = input(machineName, input, DcValue.of(down))

	/** Reports [input]'s value on [machineName] this tick, and delivers whatever that drives. */
	fun input(machineName: String, input: String, value: DcValue) {

		if (mainframe.budget.tripped) return

		val program = mainframe.programOf(machineName) ?: return

		val values = inputValues.getOrPut(machineName) { seed(machineName) }
		values[input] = value

		val eval = DcEval(values, mainframe.budget)

		// bindings sharing an expression and a mode share a latch, so they resolve once and deliver together.
		// groupBy keeps encounter order, so an overrun always drops the same statements — a pilot can only
		// learn to fly around a failure that lands in the same place every time.
		val affected = program.bindingsReading(input).groupBy { StateKey(machineName, it.source, it.mode) }

		for ((key, boundToExpression) in affected) {

			val state = states.getOrPut(key) { InputState() }

			val driven = try {

				key.source.evaluate(eval)

			} catch (_: DcOverrun) {

				trip()
				return

			} catch (typeError: DcTypeError) {

				// TODO: crash the mainframe here. A type error is a program fault, not a load fault, so
				//       rebooting into the same program just faults again — it needs its own failure mode
				//       and its own way to be cleared. Specified by the developer; until then it is
				//       reported once and the binding is skipped.
				warnTypeError(machineName, key.source, typeError)
				continue

			}

			val active = resolve(state, key.mode, driven.truthy)

			// an expression that is off and was already off has nothing to say
			if (!active && !state.delivering) continue

			// the value is what the function is told, so a `hold` binding on a list hands over the list
			val delivered = if (active) driven else DcValue.of(false)

			for (binding in boundToExpression) {

				if (!spend(mainframe.groups.membersOf(binding.target).size)) return
				deliver(binding, delivered)

			}

			state.delivering = active

		}

	}

	/** Every input [machineName] emits, at its type's zero. `not [x]` has to read false before x is
	 *  reported, and `count [x]` has to read an empty list rather than a number. */
	private fun seed(machineName: String): MutableMap<String, DcValue> {

		val emitter = mainframe.machineNamed(machineName) as? DcProgrammable ?: return mutableMapOf()

		val values = mutableMapOf<String, DcValue>()
		for ((input, type) in emitter.dataInputs) values[input] = DcValue.zeroOf(type)

		return values

	}

	/** Lets go of everything [machineName] was holding down, and leaves everything it latched. */
	fun release(machineName: String) {

		val program = mainframe.programOf(machineName) ?: return

		// expressions read the cache, not the latch state, so the inputs themselves have to go quiet.
		// each one goes back to its own type's zero, not to a number.
		inputValues[machineName]?.replaceAll { _, held -> DcValue.zeroOf(held.type) }

		for ((key, state) in states) {

			if (key.machineName != machineName) continue

			// the key is not down any more whichever mode it drove, or the next press would not read as one
			state.down = false

			// a latch is left exactly as it was found, still delivering, so it picks straight back up when
			// somebody sits down again. hold and push both let go.
			if (key.mode == DcMode.TOGGLE) continue

			if (!state.delivering) continue

			for (binding in program.bindingsDrivenBy(key.source, key.mode)) deliver(binding, DcValue.of(false))

			state.delivering = false

		}

	}

	/** Forgets every latch belonging to [machineName]. For a machine that is gone, not one that went quiet. */
	fun forget(machineName: String) {

		states.keys.removeIf { it.machineName == machineName }
		inputValues.remove(machineName)

	}

	/** Where a mode's value comes from: the key itself, its rising edge, or a latch the key flips. */
	private fun resolve(state: InputState, mode: DcMode, down: Boolean): Boolean {

		val pressed = down && !state.down
		state.down = down

		if (mode == DcMode.HOLD) return down

		// one signal per press: only the edge is on, so holding the key changes nothing after it
		if (mode == DcMode.PUSH) return pressed

		val tick = Bukkit.getCurrentTick()

		if (pressed && tick - state.lastFlipTick >= TOGGLE_COOLDOWN) {

			state.latched = !state.latched
			state.lastFlipTick = tick

		}

		return state.latched

	}

	/**
	 * Charges [operations] to the mainframe's budget. On an overrun it trips the mainframe and returns
	 * false, which is the caller's cue to stop dispatching.
	 */
	private fun spend(operations: Int): Boolean {

		if (mainframe.budget.charge(operations)) return true

		trip()
		return false

	}

	/** Takes the mainframe down, letting go of everything it was driving on the way out. */
	// a half-delivered tick welds thrusters on, so everything still delivering is released — latches
	// included, which is what makes this different from release()
	private fun trip() {

		mainframe.budget.trip()

		for ((key, state) in states) {

			if (!state.delivering) continue

			val program = mainframe.programOf(key.machineName) ?: continue

			// shutdown is never charged: a mainframe that cannot afford to let go is a stuck throttle
			for (binding in program.bindingsDrivenBy(key.source, key.mode)) deliver(binding, DcValue.of(false))

		}

		states.clear()
		inputValues.clear()

		mainframe.reportOverrun()

	}

	/** Resolves [binding] to live machines and calls the function on each of them. */
	// a broadcast does not care whether anyone is listening: a group with nothing in it, an offline
	// machine and a machine without the function are all no-ops.
	private fun deliver(binding: DcBinding, value: DcValue) {

		for (machineName in mainframe.groups.membersOf(binding.target)) {

			val programmable = mainframe.machineNamed(machineName) as? DcProgrammable ?: continue

			if (binding.function !in programmable.dataFunctions) {

				warn(machineName, binding.function)
				continue

			}

			programmable.invoke(binding.function, value)

		}

	}

	private fun warn(machineName: String, function: String) {

		if (!warned.add("$machineName:$function")) return

		Anion.plugin.logger.warning("[dcprgm] '$machineName' has no function '$function'")

	}

	/** Reports a type error once per expression. The compiler catches these whenever the emitting machine
	 *  is plugged in, so anything reaching here came from a program compiled against an absent one. */
	private fun warnTypeError(machineName: String, source: DcExpr, typeError: DcTypeError) {

		if (!warned.add("$machineName!$source")) return

		Anion.plugin.logger.warning("[dcprgm] '$machineName' $source: ${typeError.message}")

	}

}
