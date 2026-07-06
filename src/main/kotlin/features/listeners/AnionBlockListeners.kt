package dev.diena.anion.features.listeners

import dev.astralchroma.processor.annotations.Register
import dev.diena.anion.features.custom.blocks.AnionBlocks
import io.papermc.paper.event.player.PlayerPickBlockEvent
import org.bukkit.block.BlockType
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener

@Register
object AnionBlockListeners : Listener {

    @EventHandler
    fun onPlayerPickBlock(event: PlayerPickBlockEvent) {

        val block = event.block

        // must be a custom block
        if (event.block != BlockType.NOTE_BLOCK) return

        AnionBlocks

    }

}