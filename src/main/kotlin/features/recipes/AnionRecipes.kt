package dev.diena.anion.features.recipes

import dev.diena.anion.data.registry.AnionRegistryKey
import dev.diena.anion.data.registry.registries.AnionRegistries
import dev.diena.anion.features.custom.blocks.AnionBlocks
import dev.diena.anion.features.custom.items.AnionItems
import dev.diena.anion.features.custom.items.AnionVanillaItem
import dev.diena.anion.features.recipes.adapters.FurnaceFuelAdapter
import dev.diena.anion.features.recipes.adapters.FurnaceSmeltAdapter
import dev.diena.anion.features.recipes.adapters.RecipeAdapter
import dev.diena.anion.features.recipes.adapters.ShapedCraftingTableAdapter
import dev.diena.anion.features.recipes.adapters.ShapelessCraftingTableAdapter
import org.bukkit.inventory.ItemType

/**
 * Declarative recipe registrations. Mirrors [AnionItems] / [AnionBlocks]:
 * define adapter instances as `val`s and the [registerRecipe] helper
 * inserts them into the global registry.
 *
 * Adapters are the unit of registration (not raw recipes) because the same
 * generic [AnionRecipe] can be surfaced through multiple contexts (a
 * crafting table recipe vs a machine variant).
 *
 * ### USAGE EXAMPLES
 *
 * 1) SHAPED CRAFTING RECIPE
 *    Build an AnionRecipe with an Item result, then wrap it in a
 *    ShapedCraftingTableAdapter with a Bukkit-style shape + key mapping.
 *
 * ```kt
 * val MY_SHAPED = registerRecipe(
 *     ShapedCraftingTableAdapter(
 *         recipe = AnionRecipe(
 *             displayName    = "My Shaped Recipe",
 *             ingredients    = emptyList(),                        // shape/key is authoritative for crafting adapters
 *             processingTicks = 0,                                 // crafting recipes are instant
 *             result         = AnionResult.Item(AnionItems.URANIUM_BLOCK, quantity = 1),
 *         ),
 *         shape = listOf(
 *             "III",
 *             "III",
 *             "III",
 *         ),
 *         key = mapOf('I' to AnionItems.URANIUM_INGOT),
 *     )
 * )
 * ```
 *
 * 2) SHAPELESS CRAFTING RECIPE
 *    Same idea, order-independent. Ingredient list is a flat List<AnionItem>.
 *
 * ```kt
 * val MY_SHAPELESS = registerRecipe(
 *     ShapelessCraftingTableAdapter(
 *         recipe = AnionRecipe(
 *             displayName    = "My Shapeless Recipe",
 *             ingredients    = emptyList(),
 *             processingTicks = 0,
 *             result         = AnionResult.Item(AnionItems.URANIUM_INGOT, quantity = 9),
 *         ),
 *         ingredients = List(9) { AnionItems.RAW_URANIUM_ORE },
 *     )
 * )
 * ```
 *
 * 3) FURNACE SMELT
 *    processingTicks is used as the vanilla cook time.
 *
 * ```kt
 * val MY_SMELT = registerRecipe(
 *     FurnaceSmeltAdapter(
 *         recipe = AnionRecipe(
 *             displayName    = "My Smelt",
 *             ingredients    = emptyList(),
 *             processingTicks = 200,                                // vanilla default is 200
 *             result         = AnionResult.Item(AnionItems.URANIUM_INGOT, quantity = 1),
 *         ),
 *         input      = AnionItems.RAW_URANIUM_ORE,
 *         experience = 0.7f,
 *     )
 * )
 * ```
 *
 * 4) FURNACE FUEL
 *    No vanilla API; registered into an internal table and consumed by
 *    AnionRecipeListeners on FurnaceBurnEvent.
 *
 * ```kt
 * val MY_FUEL = registerRecipe(
 *     FurnaceFuelAdapter(
 *         recipe = AnionRecipe(
 *             displayName    = "Uranium Fuel",
 *             ingredients    = emptyList(),
 *             processingTicks = 0,
 *             result         = AnionResult.Item(AnionItems.URANIUM_INGOT, quantity = 0),
 *         ),
 *         fuel      = AnionItems.URANIUM_INGOT,
 *         burnTicks = 32_000,
 *         cookTimeModifier = 0.5,                                // smelts twice as fast while lit
 *     )
 * )
 * ```
 *
 * 5) MACHINE RECIPE (generic, multi-resource, starve-tolerant)
 *    Machines pull recipes from the registry and call adapter.tick(...) with
 *    whatever their buffers can supply this tick. Progress is proportional to
 *    the most-starved input.
 *
 * ```kt
 * val STEEL_INGOT = registerRecipe(
 *     MachineRecipeAdapter(
 *         recipe = AnionRecipe(
 *             displayName    = "Steel Ingot",
 *             ingredients    = listOf(
 *                 AnionIngredient(
 *                     resource      = AnionResource.Gas(AnionGasses.OXYGEN),
 *                     totalRequired = 1800L,
 *                     ratePerTick   = 18L,
 *                 ),
 *                 AnionIngredient(
 *                     resource      = AnionResource.Item(AnionItems.IRON_INGOT),
 *                     totalRequired = 500L,
 *                     ratePerTick   = 5L,
 *                 ),
 *             ),
 *             processingTicks = 100,
 *             result          = AnionResult.Item(AnionItems.STEEL_INGOT, quantity = 1),
 *         ),
 *     )
 * )
 *
 * // Per-tick call from the owning machine's tick loop:
 * val supply = mapOf(
 *     AnionResource.Gas(AnionGasses.OXYGEN).id     to oxygenBuffer.take(18L),
 *     AnionResource.Item(AnionItems.IRON_INGOT).id to ironBuffer.take(5L),
 * )
 * val tick = STEEL_INGOT.tick(supply)
 * if (tick.completed) machine.output(STEEL_INGOT.recipe.result)
 * ```
 *
 * -------------------------------------------------------------------------
 */
object AnionRecipes {

	// CRAFTING RECIPES

	// blaster pistol: 3 uranium ingots, 1 stick -> 1 blaster pistol
	val BLASTER_PISTOL_FROM_ITEM = registerRecipe(
		ShapedCraftingTableAdapter(
			recipe = AnionRecipe(
				displayName = "Blaster Pistol From Items",
				ingredients = emptyList(),
				processingTicks = 0,
				result = AnionResult.Item(AnionItems.ANION_BLASTER_PISTOL, quantity = 1),
			),
			shape = listOf(
				"   ",
				"UUU",
				"S  ",
			),
			key = mapOf(
				'U' to AnionItems.URANIUM_INGOT,
				'S' to AnionVanillaItem(ItemType.STICK.createItemStack())
			),
		)
	)

	// uranium block: 9 uranium ingots -> 1 uranium block
	val URANIUM_BLOCK_FROM_ITEM = registerRecipe(
		ShapedCraftingTableAdapter(
			recipe = AnionRecipe(
				displayName = "Uranium Block From Uranium Ingot",
				ingredients = emptyList(),
				processingTicks = 0,
				result = AnionResult.Item(AnionItems.URANIUM_BLOCK, quantity = 1),
			),
			shape = listOf(
				"III",
				"III",
				"III",
			),
			key = mapOf('I' to AnionItems.URANIUM_INGOT),
		)
	)

	// uranium ore block: 9 raw uranium ore -> 1 uranium ore block
	val URANIUM_ORE_BLOCK_FROM_ITEM = registerRecipe(
		ShapedCraftingTableAdapter(
			recipe = AnionRecipe(
				displayName = "Uranium Ore Block From Raw Uranium Ore",
				ingredients = emptyList(),
				processingTicks = 0,
				result = AnionResult.Item(AnionItems.URANIUM_ORE_BLOCK, quantity = 1),
			),
			shape = listOf(
				"OOO",
				"OOO",
				"OOO",
			),
			key = mapOf('O' to AnionItems.RAW_URANIUM_ORE),
		)
	)

	// uranium ingot: 1 uranium block -> 9 uranium ingot
	val URANIUM_INGOT_FROM_BLOCK = registerRecipe(
		ShapelessCraftingTableAdapter(
			recipe = AnionRecipe(
				displayName = "Uranium Ingot From Uranium Block",
				ingredients = emptyList(),
				processingTicks = 0,
				result = AnionResult.Item(AnionItems.URANIUM_INGOT, quantity = 9),
			),
			ingredients = listOf(AnionItems.URANIUM_BLOCK),
		)
	)

	// uranium ore: 1 uranium ore block -> 9 raw uranium ore
	val URANIUM_ORE_FROM_BLOCK = registerRecipe(
		ShapelessCraftingTableAdapter(
			recipe = AnionRecipe(
				displayName = "Raw Uranium Ore From Uranium Ore Block",
				ingredients = emptyList(),
				processingTicks = 0,
				result = AnionResult.Item(AnionItems.RAW_URANIUM_ORE, quantity = 9),
			),
			ingredients = listOf(AnionItems.URANIUM_ORE_BLOCK),
		)
	)

	// FURNACE RECIPES

	// uranium ore smelt: raw uranium ore -> uranium ingot
	val URANIUM_ORE_SMELT = registerRecipe(
		FurnaceSmeltAdapter(
			recipe = AnionRecipe(
				displayName = "Uranium Ore Smelt",
				ingredients = emptyList(),
				processingTicks = 200,
				result = AnionResult.Item(AnionItems.URANIUM_INGOT, quantity = 1),
			),
			input = AnionItems.RAW_URANIUM_ORE,
			experience = 0.7f,
		)
	)

	// uranium ore block smelt: ore block -> uranium block
	// costs a proportionally longer cook time and rewards a proportionate larger amount of xp
	val URANIUM_ORE_BLOCK_SMELT = registerRecipe(
		FurnaceSmeltAdapter(
			recipe = AnionRecipe(
				displayName = "Uranium Ore Block Smelt",
				ingredients = emptyList(),
				processingTicks = 1800,
				result = AnionResult.Item(AnionItems.URANIUM_BLOCK, quantity = 1),
			),
			input = AnionItems.URANIUM_ORE_BLOCK,
			experience = 6.3f,
		)
	)

	// test item furnace fuel: super short burn, smelts at 2x speed
	val TEST_FUEL_FUEL = registerRecipe(
		FurnaceFuelAdapter(
			recipe = AnionRecipe(
				displayName = "Test Fuel Fuel",
				ingredients = emptyList(),
				processingTicks = 0,
				result = AnionResult.Item(AnionItems.TEST_FUEL, quantity = 0),
			),
			fuel = AnionItems.TEST_FUEL,
			burnTicks = 20,
			cookTimeModifier = 0.5, // half normal processing time of whatever furnace recipe is being smelted
		)
	)

	// HELPER FUNCTIONS

	fun <T : RecipeAdapter> registerRecipe(adapter: T): T = adapter.also {
		AnionRegistries.RECIPE_REGISTRY.register(
			AnionRegistryKey(it.recipe.namespacedKey.key),
			it.recipe,
		)
		it.register()
	}

}
