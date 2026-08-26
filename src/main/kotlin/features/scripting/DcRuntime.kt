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

	/** mode state is per input per mode: two bindings on one key in one mode share a latch. */
	private data class StateKey(val machineName: String, val input: String, val mode: DcMode)

	private class InputState {

		var down = false
		var latched = false
		var lastFlipTick = 0
		var delivering = false

	}

	private val states: MutableMap<StateKey, InputState> = mutableMapOf()

	/** calls already reported as unimplemented, so a bad binding warns once instead of every tick */
	private val warned: MutableSet<String> = mutableSetOf()

	/** Reports what [input] on [machineName] is doing this tick, and delivers whatever that drives. */
	fun input(machineName: String, input: String, down: Boolean) {

		val program = mainframe.programOf(machineName) ?: return

		val bindings = program.bindingsFor(input)
		if (bindings.isEmpty()) return

		for ((mode, boundInMode) in bindings.groupBy { it.mode }) {

			val state = states.getOrPut(StateKey(machineName, input, mode)) { InputState() }
			val active = resolve(state, mode, down)

			// an input that is off and was already off has nothing to say
			if (!active && !state.delivering) continue

			for (binding in boundInMode) deliver(binding, active)
			state.delivering = active

		}

	}

	/** Lets go of everything [machineName] was holding down, and leaves everything it latched. */
	fun release(machineName: String) {

		val program = mainframe.programOf(machineName) ?: return

		for ((key, state) in states) {

			if (key.machineName != machineName) continue

			// the key is not down any more whichever mode it drove, or the next press would not read as one
			state.down = false

			// a latch is left exactly as it was found, still delivering, so it picks straight back up when
			// somebody sits down again
			if (key.mode != DcMode.HOLD) continue

			if (!state.delivering) continue

			for (binding in program.bindingsFor(key.input)) {
				if (binding.mode == key.mode) deliver(binding, false)
			}

			state.delivering = false

		}

	}

	/** Forgets every latch belonging to [machineName]. For a machine that is gone, not one that went quiet. */
	fun forget(machineName: String) {

		states.keys.removeIf { it.machineName == machineName }

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
