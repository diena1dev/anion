package dev.diena.anion.features.custom.recipes.adapters

import dev.diena.anion.Anion
import dev.diena.anion.features.custom.items.AnionItem
import dev.diena.anion.features.custom.recipes.AnionRecipe
import org.bukkit.NamespacedKey

/**
 * Adapter for a custom furnace fuel entry. There is no vanilla Bukkit API
 * for declaring new fuels, so this adapter registers into an internal table
 * that [dev.diena.anion.features.listeners.AnionRecipeListeners] consults
 * on [org.bukkit.event.inventory.FurnaceBurnEvent].
 *
 * The [recipe]'s ingredient/result fields are unused by the fuel lookup —
 * only [fuel] and [burnTicks] matter. They are kept so all recipe types
 * flow through the same registration path.
 *
 * @param recipe     Generic recipe backing this adapter (for the registry key).
 * @param fuel       Item that should burn as fuel.
 * @param burnTicks  Vanilla ticks the fuel should last (200 = 1 item smelt).
 */
class FurnaceFuelAdapter(
	override val recipe: AnionRecipe,
	val fuel: AnionItem,
	val burnTicks: Int,
) : RecipeAdapter {

	override fun register() {
		FUEL_TABLE[fuel.namespacedKey] = burnTicks
		Anion.plugin.logger.info(
			"[Recipe] Registered furnace fuel ${fuel.namespacedKey} (${burnTicks} ticks)"
		)
	}

	companion object {
		/** Populated by adapters at registration; read by the listener. */
		val FUEL_TABLE: MutableMap<NamespacedKey, Int> = mutableMapOf()
	}

}
