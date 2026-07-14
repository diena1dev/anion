package dev.diena.anion.features.listeners

import dev.astralchroma.processor.annotations.Register
import dev.diena.anion.extensions.toAnionItem
import dev.diena.anion.features.recipes.adapters.FurnaceFuelAdapter
import dev.diena.anion.features.recipes.adapters.FurnaceSmeltAdapter
import org.bukkit.block.Furnace
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockCookEvent
import org.bukkit.event.inventory.FurnaceBurnEvent
import org.bukkit.event.inventory.FurnaceStartSmeltEvent
import kotlin.math.roundToInt

/**
 * Event-driven glue for recipe adapters that vanilla Bukkit does not
 * natively support. Currently:
 *  - custom furnace fuels (see [FurnaceFuelAdapter]).
 *  - guards to prevent AnionItems from being used as vanilla fuel or
 *    smelted by vanilla recipes unless Anion has explicitly whitelisted
 *    them.
 */
@Register
object AnionRecipeListeners : Listener {

	/**
	 * Applies custom burn times for Anion fuels, and blocks any AnionItem
	 * that isn't a registered Anion fuel from burning (vanilla might
	 * otherwise accept it via Material fallback, e.g. AMETHYST_SHARD-backed
	 * items).
	 */
	@EventHandler
	fun onFurnaceBurn(event: FurnaceBurnEvent) {
		val anionItem = event.fuel.toAnionItem()

		if (anionItem == null) {
			// vanilla fuel — leave alone.
			return
		}

		val profile = FurnaceFuelAdapter.FUEL_TABLE[anionItem.namespacedKey]
		if (profile == null) {
			// Anion item, but not a whitelisted fuel: veto the burn.
			event.isCancelled = true
			return
		}

		event.isBurning = true
		event.burnTime = profile.burnTicks
	}

	/**
	 * Scales the cook time of a normal furnace smelt by the
	 * [FurnaceFuelAdapter.FuelProfile.cookTimeModifier] of whatever Anion fuel
	 * is currently powering the furnace. Vanilla fuels (and Anion fuels with a
	 * 1.0 modifier) leave the cook time untouched.
	 */
	@EventHandler
	fun onFurnaceStartSmelt(event: FurnaceStartSmeltEvent) {
		val furnace = event.block.state as? Furnace ?: return
		val fuelItem = furnace.inventory.fuel ?: return
		val anionFuel = fuelItem.toAnionItem() ?: return

		val profile = FurnaceFuelAdapter.FUEL_TABLE[anionFuel.namespacedKey] ?: return
		if (profile.cookTimeModifier == 1.0) return

		val scaled = (event.totalCookTime * profile.cookTimeModifier).roundToInt().coerceAtLeast(1)
		event.totalCookTime = scaled
	}

	/**
	 * Cancels any furnace/smoker/blast-furnace/campfire smelt whose source
	 * is an AnionItem that has not been registered as a valid Anion smelt
	 * input via [FurnaceSmeltAdapter].
	 */
	@EventHandler
	fun onBlockCook(event: BlockCookEvent) {
		val anionItem = event.source.toAnionItem() ?: return
		if (anionItem.namespacedKey !in FurnaceSmeltAdapter.SMELT_INPUTS) {
			event.isCancelled = true
		}
	}

}
