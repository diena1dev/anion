package dev.diena.anion.features.scripting

/**
 * A machine a datachannel program can see. Checked with `is`, so no machine ever changes superclass to
 * become controllable.
 *
 * The `available inputs:` and `available functions:` headers the editor draws are rendered off these two
 * lists, so there is no second declaration to drift out of sync with what the machine actually does.
 */
interface DcProgrammable {

	/** Values this machine emits, and what shape each one is. The type is what lets a program be
	 *  type-checked at save time instead of failing in flight. */
	val dataInputs: Map<String, DcType> get() = emptyMap()

	/** What other machines may call on this one. */
	val dataFunctions: List<String> get() = emptyList()

	/**
	 * Runs [function] with the value that drove it.
	 *
	 * Called every tick the value is on, and once more with a false-y value when it goes off.
	 */
	fun invoke(function: String, value: DcValue) = invoke(function, value.truthy)

	/** Runs [function] on or off. Implement [invoke] with a [DcValue] instead when the value matters. */
	fun invoke(function: String, active: Boolean) {}

}
