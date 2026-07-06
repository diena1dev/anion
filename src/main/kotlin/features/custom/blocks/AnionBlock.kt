package dev.diena.anion.features.custom.blocks

import org.bukkit.Instrument
import org.bukkit.NamespacedKey
import org.bukkit.Note
import org.bukkit.block.BlockState
import org.bukkit.block.BlockType

/**
 *
 * AnionBlocks use NamespacedKeys to identify and fetch them from Anion's registries.
 *
 */
class AnionBlock(
    val namespacedId: NamespacedKey,
    val instrument: Instrument?,
    val note: Note?,
) {
    companion object {
        fun createVanillaBlock(
            block: BlockType,
            blockState: BlockState
        ): AnionBlock {

            return AnionBlock(
                NamespacedKey(
                    "minecraft", block.key.key
                ),
                null,
                null
            )

        }

        /*fun getVanillaBlock(): BlockType {



            return
        }*/
    }

}