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

		// bindings sharing an expression and a mode share a latch, so they resolve once and deliver together.
		// groupBy keeps encounter order, so an overrun always drops the same statements — a pilot can only
		// learn to fly around a failure that lands in the same place every time.
		val affected = program.bindingsReading(input).groupBy { StateKey(machineName, it.source, it.mode) }

		for ((key, boundToExpression) in affected) {

			if (!spend(key.source.cost)) return

			val state = states.getOrPut(key) { InputState() }
			val active = resolve(state, key.mode, key.source.evaluate { values[it] ?: DcValue.FALSE }.truthy)

			// an expression that is off and was already off has nothing to say
			if (!active && !state.delivering) continue

			for (binding in boundToExpression) {

				if (!spend(mainframe.groups.membersOf(binding.target).size)) return
				deliver(binding, active)

			}

			state.delivering = active

		}

	}

	/** Every input [machineName] emits, at zero. `not [x]` has to read false before x is ever reported. */
	private fun seed(machineName: String): MutableMap<String, DcValue> {

		val emitter = mainframe.machineNamed(machineName) as? DcProgrammable ?: return mutableMapOf()

		return emitter.dataInputs.associateWithTo(mutableMapOf()) { DcValue.FALSE }

	}

	/** Lets go of everything [machineName] was holding down, and leaves everything it latched. */
	fun release(machineName: String) {

		val program = mainframe.programOf(machineName) ?: return

		// expressions read the cache, not the latch state, so the inputs themselves have to go quiet
		inputValues[machineName]?.replaceAll { _, _ -> DcValue.FALSE }

		for ((key, state) in states) {

			if (key.machineName != machineName) continue

			// the key is not down any more whichever mode it drove, or the next press would not read as one
			state.down = false

			// a latch is left exactly as it was found, still delivering, so it picks straight back up when
			// somebody sits down again
			if (key.mode != DcMode.HOLD) continue

			if (!state.delivering) continue

			for (binding in program.bindingsDrivenBy(key.source, key.mode)) deliver(binding, false)

			state.delivering = false

		}

	}

	/** Forgets every latch belonging to [machineName]. For a machine that is gone, not one that went quiet. */
	fun forget(machineName: String) {

		states.keys.removeIf { it.machineName == machineName }
		inputValues.remove(machineName)

	}

	/** Where a mode's value comes from: the key itself, or a latch the key flips. */
	private fun resolve(state: InputState, mode: DcMode, down: Boolean): Boolean {

		val pressed = down && !state.down
		state.down = down

		if (mode == DcMode.HOLD) return down

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
			for (binding in program.bindingsDrivenBy(key.source, key.mode)) deliver(binding, false)

		}

		states.clear()
		inputValues.clear()

		mainframe.reportOverrun()

	}

	/** Resolves [binding] to live machines and calls the function on each of them. */
	// a broadcast does not care whether anyone is listening: a group with nothing in it, an offline
	// machine and a machine without the function are all no-ops.
	private fun deliver(binding: DcBinding, active: Boolean) {

		for (machineName in mainframe.groups.membersOf(binding.target)) {

			val programmable = mainframe.machineNamed(machineName) as? DcProgrammable ?: continue

			if (binding.function !in programmable.dataFunctions) {

				warn(machineName, binding.function)
				continue

			}

			programmable.invoke(binding.function, active)

		}

	}

	private fun warn(machineName: String, function: String) {

		if (!warned.add("$machineName:$function")) return

		Anion.plugin.logger.warning("[dcprgm] '$machineName' has no function '$function'")

	}

}
