package dev.diena.anion.features.listeners

import dev.astralchroma.processor.annotations.Register
import dev.diena.anion.features.starship.Starship
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPhysicsEvent
import org.bukkit.event.block.BlockPlaceEvent

@Register
object AnionStarshipListeners: Listener {

	// construction listeners start
	@EventHandler
	fun onBlockPlace(event: BlockPlaceEvent) {
		val blockPlaced = event.blockPlaced

		Starship.loadedStarships.values.forEach { starship ->
			starship.addBlock(blockPlaced)
		}
	}

	@EventHandler
	fun onBlockBreak(event: BlockBreakEvent) {
		val blockBroken = event.block

		Starship.loadedStarships.values.forEach { starship ->
			starship.removeBlock(blockBroken)
		}
	}

	@EventHandler
	fun onBlockPhysics(event: BlockPhysicsEvent) {
		val block = event.block

		Starship.loadedStarships.values.forEach {
			starship ->
			starship.updateBlock(block)
		}
	}
	// construction listeners end

}
