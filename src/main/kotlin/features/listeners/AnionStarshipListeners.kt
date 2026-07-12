package dev.diena.anion.features.listeners

import dev.astralchroma.processor.annotations.Register
import dev.diena.anion.data.database.AnionPersistence
import dev.diena.anion.features.starship.Starship
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPhysicsEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent

@Register
object AnionStarshipListeners: Listener {

	// chunk lifecycle — drives lazy load/unload
	@EventHandler
	fun onChunkLoad(event: ChunkLoadEvent) {
		val chunk = event.chunk
		val world = (chunk.world as CraftWorld).handle
		AnionPersistence.loadStarshipsForChunk(world, chunk.x, chunk.z)
	}

	@EventHandler
	fun onChunkUnload(event: ChunkUnloadEvent) {
		val chunk = event.chunk
		val world = (chunk.world as CraftWorld).handle
		for ((uuid, ship) in Starship.loadedStarships.entries.toList()) {
			if (ship.level != world) continue
			if ((ship.origin.x shr 4) == chunk.x && (ship.origin.z shr 4) == chunk.z) {
				AnionPersistence.unloadStarship(uuid)
			}
		}
	}

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

		Starship.loadedStarships.values.forEach {
			starship ->
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
