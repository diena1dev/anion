package dev.diena.anion.features.listeners

import dev.astralchroma.processor.annotations.Register
import dev.diena.anion.data.database.AnionPersistence
import dev.diena.anion.extensions.vec3i
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
	@EventHandler(ignoreCancelled = true)
	fun onBlockPlace(event: BlockPlaceEvent) {

		if (Starship.applyingWorldChanges) return

		val blockPlaced = event.blockPlaced

		// first ship to claim it owns it; a block belongs to exactly one starship
		Starship.loadedStarships.values.firstOrNull { starship ->
			starship.addBlock(blockPlaced)
		}

	}

	@EventHandler(ignoreCancelled = true)
	fun onBlockBreak(event: BlockBreakEvent) {

		if (Starship.applyingWorldChanges) return

		val blockBroken = event.block

		Starship.loadedStarships.values.firstOrNull { starship ->
			starship.removeBlock(blockBroken)
		}

	}

	@EventHandler
	fun onBlockPhysics(event: BlockPhysicsEvent) {

		// starship movement writes blocks with UPDATE_NEIGHBORS, which fires this event for every neighbour of
		// every cell the ship lands on. handling those makes ships absorb each other's blocks — see
		// [Starship.applyingWorldChanges].
		if (Starship.applyingWorldChanges) return

		val block = event.block

		Starship.loadedStarships.values.firstOrNull { starship ->
			starship.updateBlock(block)
		}

	}
	// construction listeners end

}
