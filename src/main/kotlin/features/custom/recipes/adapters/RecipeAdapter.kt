package dev.diena.anion.features.custom.recipes.adapters

import dev.diena.anion.features.custom.recipes.AnionRecipe

/**
 * Adapter contract: bridges a generic [AnionRecipe] into a specific runtime
 * context (Bukkit crafting table, furnace, custom machine, etc).
 *
 * Adapters own construction of their concrete Bukkit recipe (if any) and
 * are invoked once by [dev.diena.anion.features.custom.recipes.AnionRecipes]
 * during registration.
 */
interface RecipeAdapter {

	val recipe: AnionRecipe

	/** Called once by the recipe registry after the recipe is stored. */
	fun register()

}
