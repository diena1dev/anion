package dev.diena.anion.features.recipes.adapters

import dev.diena.anion.features.custom.AnionResource
import dev.diena.anion.features.recipes.AnionIngredient
import dev.diena.anion.features.recipes.AnionRecipe

/**
 * Adapter that drives an [AnionRecipe] as a per-tick machine operation.
 *
 * TODO: energy ingredients have nothing to draw from until the energy and transport subsystems land.
 *       until then a machine should hand this adapter a [supply] that reports an unlimited amount for
 *       AnionEnergy and a [draw] that always grants it, so recipes run at full rate for free.
 *
 * @param recipe The generic recipe backing this machine operation.
 */
class MachineRecipeAdapter(
	override val recipe: AnionRecipe,
) : RecipeAdapter {

	private var opsCompleted: Double = 0.0

	override fun register() {
		// Machines pull recipes from AnionRegistries.RECIPE_REGISTRY.
		// No Bukkit binding.
	}

	/** Progress toward completion in the range [0.0, 1.0]. */
	fun progressFraction(): Double =
		(opsCompleted / recipe.processingTicks).coerceIn(0.0, 1.0)

	fun isComplete(): Boolean = opsCompleted >= recipe.processingTicks

	fun reset() {

		opsCompleted = 0.0
		recipe.ingredients.forEach(AnionIngredient::reset)

	}

	/**
	 * Advance the recipe by one tick.
	 *
	 * @param supply  How many units of a resource the machine's buffers can offer this tick.
	 * @param draw    Removes up to the requested units from the machine's buffers and returns how many
	 *                were actually taken. Progress follows this number, never the requested one, so a
	 *                buffer that comes up short can never advance the recipe further than it paid for.
	 * @return        [TickResult] describing what was consumed, the fractional progress gained, and
	 *                whether the run completed on this tick.
	 */
	fun tick(

		supply: (AnionResource) -> Long,
		draw: (AnionResource, Long) -> Long,

	): TickResult {

		if (isComplete()) {

			return TickResult(consumed = emptyMap(), progressGained = 0.0, completed = false)

		}

		// determine per-ingredient throughput ratio for this tick (bounded to demand)
		var minRatio = 1.0
		for (ingredient in recipe.ingredients) {

			val demand = ingredient.tickDemand()
			if (demand <= 0L) continue

			val available = minOf(supply(ingredient.resource), demand)
			val ratio = available.toDouble() / demand.toDouble()
			if (ratio < minRatio) minRatio = ratio

		}

		// a starved line slows the machine down, an empty one stops it dead
		if (minRatio <= 0.0) return TickResult(consumed = emptyMap(), progressGained = 0.0, completed = false)

		if (opsCompleted == 0.0) recipe.onStart()

		// consume each ingredient in proportion to the bottleneck ratio so
		// no input is over-drawn relative to the achieved progress
		val consumed = mutableMapOf<AnionResource, Long>()
		for (ingredient in recipe.ingredients) {

			val demand = ingredient.tickDemand()
			if (demand <= 0L) continue

			val wanted = (demand.toDouble() * minRatio).toLong().coerceAtLeast(0L)
			val drawn = draw(ingredient.resource, wanted)

			ingredient.feed(drawn) // debit first, then credit progress — the two can never drift apart
			if (drawn > 0L) consumed[ingredient.resource] = drawn

		}

		val progressGained = minRatio  // 1.0 op per tick at full supply
		opsCompleted += progressGained
		recipe.onTick(progressFraction())

		val completed = isComplete()
		if (completed) recipe.onComplete()

		return TickResult(consumed = consumed, progressGained = progressGained, completed = completed)

	}

	/**
	 * @param consumed        resource -> units drawn from the machine buffer this tick.
	 * @param progressGained  Fractional operations gained (0.0..1.0).
	 * @param completed       True on the tick the recipe finished.
	 */
	data class TickResult(
		val consumed: Map<AnionResource, Long>,
		val progressGained: Double,
		val completed: Boolean,
	)

}
