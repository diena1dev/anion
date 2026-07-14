package dev.diena.anion.data.registry.registries

import dev.diena.anion.data.registry.AnionRegistry
import dev.diena.anion.data.registry.AnionRegistryKey
import dev.diena.anion.data.registry.keys.AnionRegistryKeys
import dev.diena.anion.features.custom.blocks.AnionBlock
import dev.diena.anion.features.custom.energies.AnionEnergy
import dev.diena.anion.features.custom.fluids.AnionFluid
import dev.diena.anion.features.custom.gasses.AnionGas
import dev.diena.anion.features.custom.items.AnionItem
import dev.diena.anion.features.machine.MachineStructure
import dev.diena.anion.features.recipes.AnionRecipe

object AnionRegistries {

    val ITEM_REGISTRY = object : AnionRegistry<AnionItem>() {
        override val registryKey = AnionRegistryKeys.ANION_ITEM_REGISTRY
        override val all: MutableMap<AnionRegistryKey, AnionItem> = mutableMapOf()
    }

    val BLOCK_REGISTRY = object : AnionRegistry<AnionBlock>() {
        override val registryKey = AnionRegistryKeys.ANION_BLOCK_REGISTRY
        override val all: MutableMap<AnionRegistryKey, AnionBlock> = mutableMapOf()
    }

    val FLUID_REGISTRY = object : AnionRegistry<AnionFluid>() {
        override val registryKey = AnionRegistryKeys.ANION_FLUID_REGISTRY
        override val all: MutableMap<AnionRegistryKey, AnionFluid> = mutableMapOf()
    }

    val GAS_REGISTRY = object : AnionRegistry<AnionGas>() {
        override val registryKey = AnionRegistryKeys.ANION_GAS_REGISTRY
        override val all: MutableMap<AnionRegistryKey, AnionGas> = mutableMapOf()
    }

    val ENERGY_REGISTRY = object : AnionRegistry<AnionEnergy>() {
        override val registryKey = AnionRegistryKeys.ANION_ENERGY_REGISTRY
        override val all: MutableMap<AnionRegistryKey, AnionEnergy> = mutableMapOf()
    }

    val MACHINE_STRUCTURE_REGISTRY = object : AnionRegistry<MachineStructure>() {
        override val registryKey = AnionRegistryKeys.MACHINE_STRUCTURE_REGISTRY
        override val all: MutableMap<AnionRegistryKey, MachineStructure> = mutableMapOf()
    }

    val RECIPE_REGISTRY = object : AnionRegistry<AnionRecipe>() {
        override val registryKey = AnionRegistryKeys.ANION_RECIPE_REGISTRY
        override val all: MutableMap<AnionRegistryKey, AnionRecipe> = mutableMapOf()
    }

}
