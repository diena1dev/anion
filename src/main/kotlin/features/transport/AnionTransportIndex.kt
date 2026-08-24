package dev.diena.anion.features.transport

import dev.diena.anion.data.database.AnionPersistence
import dev.diena.anion.extensions.vec3i
import dev.diena.anion.features.starship.Starship
import net.minecraft.core.Vec3i
import org.bukkit.World
import org.bukkit.block.Block
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Where every transport component sits, so a pass can find one without scanning the world.
 *
 * Components are cells rather than instances — a pipe is a block and nothing else — so this is a set
 * of positions per world, persisted per chunk in the `transport` column family and paged in and out
 * with its chunk. Entries are a hint: a cell that no longer holds a component is dropped the next
 * time it is read, which is what keeps WorldEdit and other event-bypassing edits from rotting it.
 *
 * This is what lets transport run without a machine anywhere on the network. Ports are only ever
 * looked up here, never iterated to drive anything.
 *
 * TODO: cells placed without firing a block event stay invisible until something re-places them. An
 *       admin rescan command is the obvious escape hatch.
 */
object AnionTransportIndex {

	/** component cells per world uuid */
	private val cells: MutableMap<UUID, MutableSet<Vec3i>> = ConcurrentHashMap()

	/**
	 * Every component cell in [world]: the grounded ones recorded here, plus the ones riding a ship.
	 *
	 * A carried cell moves every tick, so it is never persisted and never lives in this index — its ship
	 * holds it instead, exactly as a carried machine is held by its ship rather than by MachineIndex.
	 */
	fun cellsIn(world: World): Set<Vec3i> {

		val grounded = cells[world.uid] ?: emptySet()

		val carried = Starship.loadedStarships.values
			.filter { it.level.world == world }
			.flatMap { it.transport.cells }

		if (carried.isEmpty()) return grounded

		return grounded + carried

	}

	/** Whether transport drives anything from [block], or carries anything through it. */
	fun isComponent(block: Block): Boolean = AnionTransportComponents.at(block) != null

	/** Records [block] as a component, in memory and on disk. No-op if it is not one. */
	// a component nothing drives says so itself — a decorative chain has no business on disk
	fun register(block: Block) {

		if (AnionTransportComponents.at(block)?.indexed != true) return

		val worldUid = block.world.uid
		if (!cells.computeIfAbsent(worldUid) { ConcurrentHashMap.newKeySet() }.add(block.vec3i)) return

		AnionPersistence.saveTransportCell(worldUid, block.vec3i)

	}

	/** Drops [block]'s cell. Safe to call on anything — a cell that was never indexed just misses. */
	fun unregister(block: Block) {

		val worldUid = block.world.uid
		if (cells[worldUid]?.remove(block.vec3i) != true) return

		AnionPersistence.deleteTransportCell(worldUid, block.vec3i)

	}

	/** Pages in every component recorded for a chunk. */
	fun loadChunk(world: World, chunkX: Int, chunkZ: Int) {

		val stored = AnionPersistence.transportCellsInChunk(world.uid, chunkX, chunkZ)
		if (stored.isEmpty()) return

		cells.computeIfAbsent(world.uid) { ConcurrentHashMap.newKeySet() }.addAll(stored)

	}

	/** Pages out a chunk's components. They stay on disk — this only frees memory. */
	fun unloadChunk(world: World, chunkX: Int, chunkZ: Int) {

		val worldCells = cells[world.uid] ?: return

		worldCells.removeIf { (it.x shr 4) == chunkX && (it.z shr 4) == chunkZ }

	}

}
