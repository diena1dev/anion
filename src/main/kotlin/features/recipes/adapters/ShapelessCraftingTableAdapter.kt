package dev.diena.anion.features.recipes.adapters

import dev.diena.anion.Anion
import dev.diena.anion.features.custom.items.AnionItem
import dev.diena.anion.features.recipes.AnionRecipe
import dev.diena.anion.features.recipes.AnionResult
import org.bukkit.Bukkit
import org.bukkit.inventory.RecipeChoice
import org.bukkit.inventory.ShapelessRecipe

/**
 * Adapter for a vanilla shapeless crafting-table recipe. Ingredients are
 * given as a flat list of AnionItems; each entry contributes one
 * corresponding item to the required grid contents (order-agnostic).
 *
 * The [recipe]'s [AnionRecipe.result] must be [AnionResult.Item].
 *
 * @param recipe       Generic recipe backing this adapter.
 * @param ingredients  Flat list of AnionItems required. Max 9 (vanilla rule).
 */
class ShapelessCraftingTableAdapter(
	override val recipe: AnionRecipe,
	val ingredients: List<AnionItem>,
) : RecipeAdapter {

	override fun register() {
		val result = recipe.result
		require(result is AnionResult.Item) {
			"ShapelessCraftingTableAdapter requires an Item result, got $result"
		}
		require(ingredients.size in 1..9) {
			"Shapeless recipe must have 1..9 ingredients, got ${ingredients.size}"
		}

		val bukkitRecipe = ShapelessRecipe(recipe.namespacedKey, result.item.asItemStack(result.quantity))
		for (item in ingredients) {
			bukkitRecipe.addIngredient(RecipeChoice.ExactChoice(item.asItemStack()))
		}

		Bukkit.getServer().addRecipe(bukkitRecipe)
		Anion.plugin.logger.info("[Recipe] Registered shapeless crafting recipe ${recipe.namespacedKey}")
	}

}
