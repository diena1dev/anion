package dev.diena.anion.features.custom.items

import dev.diena.anion.features.custom.blocks.AnionMushroomCustomBlock
import dev.diena.anion.features.custom.blocks.MushroomType
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.component.BlockItemStateProperties
import net.minecraft.world.level.block.HugeMushroomBlock
import org.bukkit.block.BlockFace
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.inventory.ItemType

@Suppress("UnstableApiUsage")
class AnionMushroomBlockItem(val anionBlock: AnionMushroomCustomBlock, val stacksTo: Int = anionBlock.stacksTo) : AnionItem(
    displayName = anionBlock.namespacedKey.key,
    itemRepresentation = when (anionBlock.mushroomType) {
        MushroomType.BROWN -> ItemType.BROWN_MUSHROOM_BLOCK
        MushroomType.RED -> ItemType.RED_MUSHROOM_BLOCK
    },
    styledDisplayName = anionBlock.styledDisplayName,
    namespacedKey = anionBlock.namespacedKey,
    stacksTo = stacksTo
) {
    init {
        val blockStateProps = BlockItemStateProperties.EMPTY
            .with(HugeMushroomBlock.UP, BlockFace.UP in anionBlock.faces)
            .with(HugeMushroomBlock.DOWN, BlockFace.DOWN in anionBlock.faces)
            .with(HugeMushroomBlock.NORTH, BlockFace.NORTH in anionBlock.faces)
            .with(HugeMushroomBlock.SOUTH, BlockFace.SOUTH in anionBlock.faces)
            .with(HugeMushroomBlock.EAST, BlockFace.EAST in anionBlock.faces)
            .with(HugeMushroomBlock.WEST, BlockFace.WEST in anionBlock.faces)
        CraftItemStack.unwrap(internalItemStack).set(DataComponents.BLOCK_STATE, blockStateProps)
    }
}
