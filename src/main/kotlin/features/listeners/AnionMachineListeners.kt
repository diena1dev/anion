package dev.diena.anion.features.listeners

import dev.astralchroma.processor.annotations.Register
import dev.diena.anion.data.database.AnionPersistence
import dev.diena.anion.features.machine.Machine
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent

/**
 * Chunk lifecycle for machines, mirroring [AnionStarshipListeners]. A machine lives and dies with the
 * chunk its **core block** sits in, which is the same chunk the `machine_chunks` index keys on.
 */
@Register
object AnionMachineListeners: Listener {

	@EventHandler
	fun onChunkLoad(event: ChunkLoadEvent) {

		val chunk = event.chunk
		val world = (chunk.world as CraftWorld).handle
		AnionPersistence.loadMachinesForChunk(world, chunk.x, chunk.z)

	}

	@EventHandler
	fun onChunkUnload(event: ChunkUnloadEvent) {

		val chunk = event.chunk
		val world = (chunk.world as CraftWorld).handle

		for ((uuid, machine) in Machine.activeMachines.entries.toList()) {

			if (machine.level != world) continue
			if ((machine.origin.x shr 4) == chunk.x && (machine.origin.z shr 4) == chunk.z) {
				// carried machines remain loaded until the starship detaches them
				AnionPersistence.unloadMachine(uuid)
			}

		}

	}

}
