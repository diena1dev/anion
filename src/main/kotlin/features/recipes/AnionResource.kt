package dev.diena.anion.features.recipes

import dev.diena.anion.features.custom.energies.AnionEnergy
import dev.diena.anion.features.custom.fluids.AnionFluid
import dev.diena.anion.features.custom.gasses.AnionGas
import dev.diena.anion.features.custom.items.AnionItem

/**
 * Sealed hierarchy of any consumable/producible resource kind referenced by
 * an [AnionRecipe]. Wraps existing Anion resource classes; amounts live on
 * [AnionIngredient] or [AnionResult] to keep resource references reusable.
 */
sealed interface AnionResource {

	/** Stable identifier used for equality / lookup within a recipe run. */
	val id: String

	data class Item(val item: AnionItem) : AnionResource {
		override val id = "item:${item.namespacedKey}"
	}

	data class Gas(val gas: AnionGas) : AnionResource {
		override val id = "gas:${System.identityHashCode(gas)}"
	}

	data class Fluid(val fluid: AnionFluid) : AnionResource {
		override val id = "fluid:${System.identityHashCode(fluid)}"
	}

	data class Energy(val energy: AnionEnergy) : AnionResource {
		override val id = "energy:${System.identityHashCode(energy)}"
	}

}
