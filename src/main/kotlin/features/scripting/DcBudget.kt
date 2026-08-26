package dev.diena.anion.features.scripting

import org.bukkit.Bukkit

/**
 * One mainframe's per-tick compute allowance.
 *
 * Spend is counted in dclang operations, never in milliseconds: the same program has to fail on the same
 * tick on every server, or an overrun is a bug report instead of something a pilot can learn to fly around.
 */
class DcBudget private constructor(val limit: Int) {

	companion object {

		fun new(limit: Int): DcBudget = DcBudget(limit)

		/** ticks a tripped mainframe stays dead before it comes back clean */
		const val REBOOT_TICKS = 200

		/** ticks a peak reading survives before it falls back. long enough to read off a sign. */
		const val PEAK_WINDOW = 100

	}

	private var chargedOnTick = -1
	private var trippedUntil = 0
	private var peakOnTick = 0

	/** operations spent this tick */
	var spent: Int = 0; private set

	/** highest spend any one tick reached in the last [PEAK_WINDOW] ticks */
	var peak: Int = 0; private set

	/** true while the mainframe is rebooting and dispatching nothing */
	val tripped: Boolean get() = Bukkit.getCurrentTick() < trippedUntil

	/** ticks left on the reboot, 0 when it is running */
	val rebootRemaining: Int get() = (trippedUntil - Bukkit.getCurrentTick()).coerceAtLeast(0)

	/** Charges [operations]. Returns false once the allowance is gone, which is the caller's cue to trip. */
	fun charge(operations: Int): Boolean {

		// the allowance rolls over on first use rather than on the mainframe's own tick: machines tick in
		// no particular order, and a seat that ticks first must not be spending last tick's remainder
		val tick = Bukkit.getCurrentTick()
		if (tick != chargedOnTick) {

			chargedOnTick = tick
			spent = 0

		}

		spent += operations

		if (spent > peak) {

			peak = spent
			peakOnTick = tick

		}

		return spent <= limit

	}

	/** Lets the peak reading fall once it is older than [PEAK_WINDOW]. Driven from the mainframe's tick,
	 *  so an idle mainframe's readout drops back to zero instead of sitting on an old high. */
	fun agePeak() {

		val tick = Bukkit.getCurrentTick()
		if (tick - peakOnTick < PEAK_WINDOW) return

		peak = 0
		peakOnTick = tick

	}

	/** Takes the mainframe down for [REBOOT_TICKS]. */
	fun trip() {

		trippedUntil = Bukkit.getCurrentTick() + REBOOT_TICKS

	}

	/** Clears the trip and the counters. For a mainframe that was just assembled, not one that recovered. */
	fun reset() {

		trippedUntil = 0
		chargedOnTick = -1
		peakOnTick = 0
		spent = 0
		peak = 0

	}

}
