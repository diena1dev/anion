package dev.diena.anion.features.custom.items

import dev.diena.anion.features.custom.blocks.AnionBlock
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.component.BlockItemStateProperties
import net.minecraft.world.level.block.NoteBlock
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument
import org.bukkit.craftbukkit.block.data.CraftBlockData
import org.bukkit.craftbukkit.inventory.CraftItemStack
import org.bukkit.inventory.ItemType

@Suppress("UnstableApiUsage")
class AnionBlockItem(val anionBlock: AnionBlock, val stacksTo: Int = anionBlock.stacksTo) : AnionItem(
    displayName = anionBlock.namespacedKey.key,
    itemRepresentation = ItemType.NOTE_BLOCK,
    styledDisplayName = anionBlock.styledDisplayName,
    namespacedKey = anionBlock.namespacedKey,
    stacksTo = stacksTo
) {
    init {
        val nmsInstrument = CraftBlockData.toVanilla(anionBlock.instrument, NoteBlockInstrument::class.java)
        val blockStateProps = BlockItemStateProperties.EMPTY
            .with(NoteBlock.INSTRUMENT, nmsInstrument)
            .with(NoteBlock.NOTE, anionBlock.note)
        CraftItemStack.unwrap(internalItemStack).set(DataComponents.BLOCK_STATE, blockStateProps)
    }
}
