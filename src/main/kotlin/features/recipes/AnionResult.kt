package dev.diena.anion.features.recipes

import dev.diena.anion.features.custom.energies.AnionEnergy
import dev.diena.anion.features.custom.fluids.AnionFluid
import dev.diena.anion.features.custom.gasses.AnionGas
import dev.diena.anion.features.custom.items.AnionItem

/**
 * The output side of an [AnionRecipe]. Adapters interpret these:
 * crafting/furnace adapters accept only [Item]; machines accept every kind
 * (including [Compound] which yields more than one output of possibly
 * mixed types).
 */
sealed interface AnionResult {

	data class Item(val item: AnionItem, val quantity: Int = 1) : AnionResult
	data class Gas(val gas: AnionGas, val amount: Long) : AnionResult
	data class Fluid(val fluid: AnionFluid, val amount: Long) : AnionResult
	data class Energy(val energy: AnionEnergy, val amount: Long) : AnionResult

	/**
	 * Multi-output result. Useful for machines that emit an item + waste gas,
	 * or two ingots at once, etc.
	 */
	class Compound private constructor(val results: List<AnionResult>) : AnionResult {
		class Builder {
			private val parts = mutableListOf<AnionResult>()
			fun item(item: AnionItem, quantity: Int = 1) = apply { parts += Item(item, quantity) }
			fun gas(gas: AnionGas, amount: Long) = apply { parts += Gas(gas, amount) }
			fun fluid(fluid: AnionFluid, amount: Long) = apply { parts += Fluid(fluid, amount) }
			fun energy(energy: AnionEnergy, amount: Long) = apply { parts += Energy(energy, amount) }
			fun build(): Compound = Compound(parts.toList())
		}

		companion object {
			fun build(block: Builder.() -> Unit): Compound = Builder().apply(block).build()
		}
	}

}
