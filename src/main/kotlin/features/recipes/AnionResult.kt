package dev.diena.anion.features.recipes

import dev.diena.anion.features.custom.energies.AnionEnergy
import dev.diena.anion.features.custom.fluids.AnionFluid
import dev.diena.anion.features.custom.gasses.AnionGas
import dev.diena.anion.features.custom.items.AnionItem

/**
 * The output side of an [AnionRecipe].
 */
sealed interface AnionResult {

	/** singleton results */
	data class Item(val item: AnionItem, val quantity: Int = 1) : AnionResult  // item result
	data class Gas(val gas: AnionGas, val amount: Long) : AnionResult          // gas result
	data class Fluid(val fluid: AnionFluid, val amount: Long) : AnionResult    // fluid result
	data class Energy(val energy: AnionEnergy, val amount: Long) : AnionResult // energy result
	data class Action(val action: () -> Unit) : AnionResult                    // what's essentially a function call result

	/** multiple results */
	class Compound private constructor(val results: List<AnionResult>) : AnionResult {
		class Builder {
			private val parts = mutableListOf<AnionResult>()
			fun item(item: AnionItem, quantity: Int = 1)  = apply { parts += Item(item, quantity) }
			fun gas(gas: AnionGas, amount: Long)          = apply { parts += Gas(gas, amount) }
			fun fluid(fluid: AnionFluid, amount: Long)    = apply { parts += Fluid(fluid, amount) }
			fun energy(energy: AnionEnergy, amount: Long) = apply { parts += Energy(energy, amount) }
			fun action(action: () -> Unit)                = apply { parts += Action(action) }
			fun build(): Compound                         = Compound(parts.toList())
		}

		companion object {
			fun build(block: Builder.() -> Unit): Compound = Builder().apply(block).build()
		}

	}

}
