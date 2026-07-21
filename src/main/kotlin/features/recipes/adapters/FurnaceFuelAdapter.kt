package dev.diena.anion.features.recipes.adapters

import dev.diena.anion.Anion
import dev.diena.anion.features.custom.items.AnionItem
import dev.diena.anion.features.recipes.AnionRecipe
import dev.diena.anion.features.listeners.AnionRecipeListeners
import org.bukkit.NamespacedKey
import org.bukkit.event.inventory.FurnaceBurnEvent

/**
 * Adapter for a custom furnace fuel entry. There is no vanilla Bukkit API
 * for declaring new fuels, so this adapter registers into an internal table
 * that [AnionRecipeListeners] consults on [FurnaceBurnEvent].
 *
 * The [recipe]'s ingredient/result fields are unused by the fuel lookup —
 * only [fuel], [burnTicks], and [cookTimeModifier] matter. They are kept so
 * all recipe types flow through the same registration path.
 *
 * @param recipe            Generic recipe backing this adapter (for the registry key).
 * @param fuel              Item that should burn as fuel.
 * @param burnTicks         Vanilla ticks the fuel should last (200 = 1 item smelt).
 * @param cookTimeModifier  Multiplier applied to a normal furnace recipe's
 *                          cook time (AnionRecipe.processingTicks) while this
 *                          fuel is powering the furnace. 1.0 = vanilla speed,
 *                          0.5 = smelts twice as fast, 2.0 = half speed.
 */
class FurnaceFuelAdapter(

	override val recipe: AnionRecipe,
	val fuel: AnionItem,
	val burnTicks: Int,
	val cookTimeModifier: Double = 1.0,

) : RecipeAdapter {

	override fun register() {
		FUEL_TABLE[fuel.namespacedKey] = FuelProfile(burnTicks, cookTimeModifier)
		Anion.plugin.logger.info(
			"[Recipe] Registered furnace fuel ${fuel.namespacedKey} " +
				"(${burnTicks} ticks, x${cookTimeModifier} cook time)"
		)
	}

	companion object {

		/**
		 * Behavior of a registered Anion fuel.
		 *
		 * @param burnTicks         How long the fuel burns.
		 * @param cookTimeModifier  Multiplier applied to smelt cook time while lit.
		 */
		data class FuelProfile(
			val burnTicks: Int,
			val cookTimeModifier: Double,
		)

		/** Populated by adapters at registration; read by the listener. */
		val FUEL_TABLE: MutableMap<NamespacedKey, FuelProfile> = mutableMapOf()

	}

}
