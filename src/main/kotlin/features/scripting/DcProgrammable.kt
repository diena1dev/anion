package dev.diena.anion.features.scripting

/**
 * A machine a datachannel program can see. Checked with `is`, so no machine ever changes superclass to
 * become controllable.
 *
 * The `available inputs:` and `available functions:` headers the editor draws are rendered off these two
 * lists, so there is no second declaration to drift out of sync with what the machine actually does.
 */
interface DcProgrammable {

	/** Values this machine emits. Declared and listed in v0.1, consumed by readouts in v0.2. */
	val dataInputs: List<String> get() = emptyList()

	/** What other machines may call on this one. */
	val dataFunctions: List<String> get() = emptyList()

	/**
	 * Runs [function]. [active] is the value behind the input that called it — held down, or latched on.
	 *
	 * Called every tick the value is on, and once more with false when it goes off. Arguments land in
	 * v0.2; until then a machine decides its own step size.
	 */
	fun invoke(function: String, active: Boolean) {}

}
