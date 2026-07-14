package dev.diena.anion.features.recipes.adapters

import dev.diena.anion.features.recipes.AnionIngredient
import dev.diena.anion.features.recipes.AnionRecipe

/**
 * Adapter that drives an [AnionRecipe] as a per-tick machine operation.
 *
 * Not registered with Bukkit; instead the owning machine's tick loop calls
 * [tick] each server tick with the amount currently available in the
 * machine's internal buffers for each ingredient. Progress is starve-tolerant
 * and proportional per the README rules:
 *
 *   - Each ingredient tries to consume up to its ratePerTick from the
 *     supplied buffer.
 *   - Recipe processing advances by the fraction
 *       min over ingredients of (consumed_i / ratePerTick_i)
 *     so that under-feeding one input throttles the whole run without
 *     wasting other inputs (their unconsumed remainder stays in the buffer).
 *
 * @param recipe  The generic recipe backing this machine operation.
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
	 * @param available  Map of resource.id -> units currently available from
	 *                   the machine's buffers this tick.
	 * @return           [TickResult] describing what was consumed, the
	 *                   fractional progress gained, and whether the run
	 *                   completed on this tick.
	 */
	fun tick(available: Map<String, Long>): TickResult {
		if (isComplete()) {
			return TickResult(consumed = emptyMap(), progressGained = 0.0, completed = false)
		}

		if (opsCompleted == 0.0) recipe.onStart()

		// determine per-ingredient throughput ratio for this tick (bounded to demand)
		var minRatio = 1.0
		val demands = recipe.ingredients.map { ing ->
			val demand = ing.tickDemand()
			if (demand <= 0L) return@map Triple(ing, 0L, 1.0)
			val supply = available[ing.resource.id] ?: 0L
			val actual = minOf(supply, demand)
			val ratio = actual.toDouble() / demand.toDouble()
			if (ratio < minRatio) minRatio = ratio
			Triple(ing, actual, ratio)
		}

		// consume each ingredient in proportion to the bottleneck ratio so
		// no input is over-drawn relative to the achieved progress
		val consumed = mutableMapOf<String, Long>()
		for ((ing, _, _) in demands) {
			val demand = ing.tickDemand()
			if (demand <= 0L) continue
			val toDraw = (demand.toDouble() * minRatio).toLong().coerceAtLeast(0L)
			val actuallyDrawn = ing.feed(toDraw)
			if (actuallyDrawn > 0L) consumed[ing.resource.id] = actuallyDrawn
		}

		val progressGained = minRatio  // 1.0 op per tick at full supply
		opsCompleted += progressGained
		recipe.onTick(progressFraction())

		val completed = isComplete()
		if (completed) recipe.onComplete()

		return TickResult(consumed = consumed, progressGained = progressGained, completed = completed)
	}

	/**
	 * @param consumed        resource.id -> units drawn from the machine buffer this tick.
	 * @param progressGained  Fractional operations gained (0.0..1.0).
	 * @param completed       True on the tick the recipe finished.
	 */
	data class TickResult(
		val consumed: Map<String, Long>,
		val progressGained: Double,
		val completed: Boolean,
	)

}
