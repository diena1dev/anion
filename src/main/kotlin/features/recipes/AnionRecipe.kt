package dev.diena.anion.features.recipes

import dev.diena.anion.Anion
import org.bukkit.NamespacedKey

/**
 * Generic recipe definition. Purely a data description; execution semantics
 * live in the associated adapter (crafting, furnace, machine, etc).
 *
 * Modeled after [dev.diena.anion.features.custom.items.AnionItem] and
 * [dev.diena.anion.features.custom.blocks.AnionBlock]: an open class with
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

	open fun onStart() { startHandler?.invoke() }
	open fun onTick(progressFraction: Double) { tickHandler?.invoke(progressFraction) }
	open fun onComplete() { completeHandler?.invoke() }
	open fun onAdd() {}
	open fun onRemove() {}

}
