package dev.diena.anion.features.recipes

import dev.diena.anion.Anion
import org.bukkit.NamespacedKey
import dev.diena.anion.features.custom.items.AnionItem
import dev.diena.anion.features.custom.blocks.AnionBlock

/**
 * Generic recipe definition. Purely a data description; execution semantics
 * live in the associated adapter (crafting, furnace, machine, etc).
 *
 * Modeled after [AnionItem] and
 * [AnionBlock]: an open class with
 * optional handler lambdas and a namespaced key derived from the display
 * name.
 *
 * @param displayName      Required: Display name of the recipe.
 * @param ingredients      Required: Resource demands. For instant recipes
 *                         (crafting) rates/totals may be zero — the adapter
 *                         reinterprets the list as a shape/ingredient set.
 * @param processingTicks  Required: Time to complete under full input. Ignored
 *                         by instant adapters; furnace/machine adapters honor
 *                         it as cook time / ops-per-tick baseline.
 * @param result           Required: Output description.
 * @param namespacedKey    Optional: Override the derived key.
 */
open class AnionRecipe(

	val displayName: String,
	val ingredients: List<AnionIngredient>,
	val processingTicks: Int,
	val result: AnionResult,
	val namespacedKey: NamespacedKey = NamespacedKey(Anion.NAMESPACE, displayName.replace(" ", "_").lowercase()),

	private val startHandler: (() -> Unit)? = null,
	private val tickHandler: ((progressFraction: Double) -> Unit)? = null,
	private val completeHandler: (() -> Unit)? = null,

) {

	/**
	 * A run-scoped copy: same definition, its own [AnionIngredient] progress counters.
	 *
	 * A registered recipe is one object shared by every machine that smelts it, and an ingredient
	 * tracks how much of itself has been fed in. Two machines running the same recipe off the shared
	 * instance would advance each other's progress, so each run takes one of these.
	 */
	// handlers stay on the source, so a copy still fires whatever the original was given
	open fun newRun(): AnionRecipe {

		val source = this

		return object : AnionRecipe(
			displayName,
			ingredients.map { AnionIngredient(it.resource, it.totalRequired, it.ratePerTick) },
			processingTicks,
			result,
			namespacedKey,
		) {

			override fun onStart() = source.onStart()
			override fun onTick(progressFraction: Double) = source.onTick(progressFraction)
			override fun onComplete() = source.onComplete()

		}

	}

	open fun onStart() { startHandler?.invoke() }
	open fun onTick(progressFraction: Double) { tickHandler?.invoke(progressFraction) }
	open fun onComplete() { completeHandler?.invoke() }
	open fun onAdd() {}
	open fun onRemove() {}

}
