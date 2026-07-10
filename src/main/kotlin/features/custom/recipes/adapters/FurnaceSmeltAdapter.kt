package dev.diena.anion.features.custom.recipes.adapters

import dev.diena.anion.Anion
import dev.diena.anion.features.custom.items.AnionItem
import dev.diena.anion.features.custom.recipes.AnionRecipe
import dev.diena.anion.features.custom.recipes.AnionResult
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.inventory.FurnaceRecipe
import org.bukkit.inventory.RecipeChoice

/**
 * Adapter for a vanilla furnace smelting recipe. Uses
 * [AnionRecipe.processingTicks] as the cook time so machines and vanilla
 * furnace behavior stay consistent.
 *
 * @param recipe      Generic recipe. Result must be [AnionResult.Item].
 * @param input       Input AnionItem to smelt.
 * @param experience  Vanilla XP dropped when the smelt completes.
 */
class FurnaceSmeltAdapter(
	override val recipe: AnionRecipe,
	val input: AnionItem,
	val experience: Float = 0.0f,
) : RecipeAdapter {

	override fun register() {
		val result = recipe.result
		require(result is AnionResult.Item) {
			"FurnaceSmeltAdapter requires an Item result, got $result"
		}

		val bukkitRecipe = FurnaceRecipe(
			recipe.namespacedKey,
			result.item.asItemStack(result.quantity),
			RecipeChoice.ExactChoice(input.asItemStack()),
			experience,
			recipe.processingTicks,
		)

		Bukkit.getServer().addRecipe(bukkitRecipe)
		SMELT_INPUTS += input.namespacedKey
		Anion.plugin.logger.info("[Recipe] Registered furnace smelt recipe ${recipe.namespacedKey}")
	}

	companion object {
		/**
		 * Set of AnionItem keys that Anion has explicitly whitelisted as
		 * smelt inputs. AnionRecipeListeners cancels any BlockCookEvent
		 * whose source is an AnionItem that is NOT present here.
		 */
		val SMELT_INPUTS: MutableSet<NamespacedKey> = mutableSetOf()
	}

}
