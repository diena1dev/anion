package dev.diena.anion.features.recipes

import kotlin.math.min

/**
 * A single input demand for an [AnionRecipe].
 *
 * @param resource       Resource kind being consumed.
 * @param totalRequired  Total units required across the whole recipe run.
 * @param ratePerTick    Maximum units the recipe will draw per tick.
 *
 * Tracks its own progress. Machines call [feed] each tick with whatever the
 * buffers can supply; the ingredient will consume up to [ratePerTick]
 * bounded by the remaining total.
 */
class AnionIngredient(
	val resource: AnionResource,
	val totalRequired: Long,
	val ratePerTick: Long,
) {

	var progress: Long = 0L
		private set

	fun remaining(): Long = totalRequired - progress

	/** Amount this ingredient will attempt to draw on the current tick. */
	fun tickDemand(): Long = min(ratePerTick, remaining())

	/**
	 * Push [available] units of the corresponding resource in. Returns how
	 * much was actually consumed (0..tickDemand).
	 */
	fun feed(available: Long): Long {
		val actual = min(available, tickDemand())
		progress += actual
		return actual
	}

	fun isSatisfied(): Boolean = progress >= totalRequired

	fun reset() { progress = 0L }

}
