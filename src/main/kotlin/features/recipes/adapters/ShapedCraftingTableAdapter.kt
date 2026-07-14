package dev.diena.anion.features.recipes.adapters

import dev.diena.anion.Anion
import dev.diena.anion.features.custom.items.AnionItem
import dev.diena.anion.features.recipes.AnionRecipe
import dev.diena.anion.features.recipes.AnionResult
import org.bukkit.Bukkit
import org.bukkit.inventory.RecipeChoice
import org.bukkit.inventory.ShapedRecipe
import kotlin.collections.iterator

/**
 * Adapter for a vanilla shaped crafting-table recipe. Ingredients are
 * described via [shape] rows + a [key] mapping (matching Bukkit's own
 * [ShapedRecipe] API) rather than via the generic ingredient list, because
 * position matters here.
 *
 * The [recipe]'s [AnionRecipe.result] must be [AnionResult.Item] — crafting
 * tables cannot emit gas/fluid/energy.
 *
 * @param recipe  Generic recipe backing this adapter.
 * @param shape   Row strings, 1-3 rows of up to 3 chars each (Bukkit rules).
 * @param key     Char -> AnionItem mapping used in [shape]. Whitespace = empty.
 */
class ShapedCraftingTableAdapter(
	override val recipe: AnionRecipe,
	val shape: List<String>,
	val key: Map<Char, AnionItem>,
) : RecipeAdapter {

	override fun register() {
		val result = recipe.result
		require(result is AnionResult.Item) {
			"ShapedCraftingTableAdapter requires an Item result, got $result"
		}

		val bukkitRecipe = ShapedRecipe(recipe.namespacedKey, result.item.asItemStack(result.quantity))
			.shape(*shape.toTypedArray())

		for ((char, item) in key) {
			bukkitRecipe.setIngredient(char, RecipeChoice.ExactChoice(item.asItemStack()))
		}

		Bukkit.getServer().addRecipe(bukkitRecipe)
		Anion.plugin.logger.info("[Recipe] Registered shaped crafting recipe ${recipe.namespacedKey}")
	}

}
