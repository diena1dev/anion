package dev.diena.anion.features.transport

import dev.diena.anion.extensions.CARTESIAN_FACES
import dev.diena.anion.extensions.anionBlock
import dev.diena.anion.extensions.anionFacing
import dev.diena.anion.extensions.drawItem
import dev.diena.anion.extensions.hasRoomFor
import dev.diena.anion.extensions.itemKeys
import dev.diena.anion.extensions.plus
import dev.diena.anion.extensions.pushItem
import dev.diena.anion.extensions.vec3i
import dev.diena.anion.features.custom.ItemKey
import dev.diena.anion.features.custom.blocks.AnionBlocks
import dev.diena.anion.features.custom.blocks.AnionDirectionalBlock
import dev.diena.anion.features.machine.Machine
import dev.diena.anion.features.machine.component.MachineBuffer
import dev.diena.anion.features.machine.component.MachinePort
import dev.diena.anion.features.machine.machine_types.PortedMachine
import net.minecraft.core.Vec3i
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.block.Container
import org.bukkit.inventory.Inventory

/**
 * Moves items between machine bus ports along conduit runs.
 *
 * Flow is the conduit's own facing: a straight conduit only takes items in through its back face and
 * only passes them out of its front, so turning a corner is what the junction is for. That makes the
 * network a directed graph read straight off the world, with nothing to configure and nothing to
 * cache — which is also why breaking a conduit needs no invalidation.
 *
 * Vanilla containers join in at both ends. A container touching a conduit's output face is a valid
 * drop-off, and a crafting table behind a run is an import node that pulls from any container touching
 * *it* — so a chest feeds the network through a crafting table, and any chest catches what comes out.
 *
 * TODO: routes are re-walked from scratch every pass. Once networks get large this wants a cached
 *       graph rebuilt off block events, the same way MachineIndex handles machine cells.
 * TODO: discovery is anchored on machine bus ports, because nothing indexes conduits in the world. A
 *       run with no machine anywhere on it (chest -> table -> conduit -> chest) is therefore invisible.
 *       Fixing it properly means a persisted conduit index, the same shape as the machine_chunks CF.
 * TODO: items only. Gas, fluid and energy get their own conduit families later.
 */
object AnionTransport {

	/** conduit blocks walked before a route is abandoned */
	private const val MAX_ROUTE_LENGTH = 64

	/** items a single port hands off per pass */
	private const val THROUGHPUT = 64L

	/** somewhere a run can put items down */
	private fun interface Sink {

		/** puts up to [units] of [key] in, and returns how many landed */
		fun push(key: ItemKey, units: Long): Long

	}

	/** One transport pass across every loaded world. Main thread — this reads and writes the world. */
	fun tick() {

		for (world in Bukkit.getWorlds()) {

			val ports = busPortsIn(world)
			if (ports.isEmpty()) continue // ports are what anchors discovery, so no ports means no network

			for ((cell, port) in ports) {

				val buffer = port.buffer() ?: continue // broken machine, or an unbound port

				// push what we hold outward, then top up from anything importing upstream
				if (buffer.contents().isNotEmpty()) drain(world, cell, buffer, ports)
				fill(world, cell, buffer)

			}

		}

	}

	/** Every bus port in [world], by the world cell it occupies. */
	// built per pass rather than indexed, so it picks up ship-carried machines too — their cells move
	// every tick and are deliberately absent from MachineIndex.
	private fun busPortsIn(world: World): Map<Vec3i, MachinePort> {

		val ports = HashMap<Vec3i, MachinePort>()

		for (machine in Machine.activeMachines.values) {

			if (machine.level.world != world) continue
			if (!machine.intact) continue

			val ported = machine as? PortedMachine ?: continue

			for (port in ported.ports.values) {
				if (port.kind != MachinePort.Kind.BUS) continue
				ports[machine.localToWorld(port.offset)] = port
			}

		}

		return ports

	}

	/** Pushes what [sourceBuffer] holds into every conduit run leaving [sourceCell]. */
	private fun drain(

		world: World,
		sourceCell: Vec3i,
		sourceBuffer: MachineBuffer,
		ports: Map<Vec3i, MachinePort>,

	) {

		for (direction in CARTESIAN_FACES) {

			val conduitCell = sourceCell + direction.vec3i

			// the conduit's face touching this port is the one opposite the direction we stepped in
			if (!acceptsFrom(world, conduitCell, direction.oppositeFace)) continue

			// snapshot: handing off mutates the buffer we are reading
			for (resource in sourceBuffer.contents().keys.toList()) {

				val key = resource as? ItemKey ?: continue

				val destination = route(world, conduitCell, direction.oppositeFace, key, ports, sourceCell, HashSet(), 0)
					?: continue

				handOff(sourceBuffer, destination, key)

			}

		}

	}

	/** Whether the conduit at [cell] takes items in through its [face] side. */
	private fun acceptsFrom(world: World, cell: Vec3i, face: BlockFace): Boolean {

		val block = world.getBlockAt(cell.x, cell.y, cell.z)

		return when (block.anionBlock) {

			null -> false
			AnionBlocks.COPPER_CONDUIT_JUNCTION -> true
			is AnionDirectionalBlock -> block.anionFacing?.oppositeFace == face

			else -> false

		}

	}

	/**
	 * Walks the conduit at [cell], entered through its [entryFace], to the first place that will take
	 * [key]. Returns null when the run dead-ends or nothing on it has room.
	 */
	// depth-first with a shared visited set: it finds a path rather than the best one, which is all a
	// conduit run needs. the visited set is also what stops a junction loop spinning forever.
	private fun route(

		world: World,
		cell: Vec3i,
		entryFace: BlockFace,
		key: ItemKey,
		ports: Map<Vec3i, MachinePort>,
		sourceCell: Vec3i,
		visited: MutableSet<Vec3i>,
		depth: Int,

	): Sink? {

		if (depth > MAX_ROUTE_LENGTH) return null
		if (!visited.add(cell)) return null

		val exits = exitsOf(world, cell, entryFace) ?: return null

		for (exit in exits) {

			val next = cell + exit.vec3i
			if (next == sourceCell) continue // straight back where it came from is not transport

			sinkAt(world, next, key, ports)?.let { return it }

			// a port or container ends the branch either way — a run does not tunnel through one
			if (ports.containsKey(next) || containerAt(world, next) != null) continue

			route(world, next, exit.oppositeFace, key, ports, sourceCell, visited, depth + 1)?.let { return it }

		}

		return null

	}

	/** Where a conduit at [cell] entered through [entryFace] can pass items on to, or null if it cannot. */
	private fun exitsOf(world: World, cell: Vec3i, entryFace: BlockFace): List<BlockFace>? {

		val block = world.getBlockAt(cell.x, cell.y, cell.z)

		return when (block.anionBlock) {

			null -> null
			AnionBlocks.COPPER_CONDUIT_JUNCTION -> CARTESIAN_FACES.filter { it != entryFace }

			is AnionDirectionalBlock -> {

				val facing = block.anionFacing ?: return null
				if (entryFace != facing.oppositeFace) return null // entered against the flow

				listOf(facing)

			}

			else -> null

		}

	}

	/** Whatever at [cell] would take [key] — a bus port, or a vanilla container on an open side. */
	private fun sinkAt(world: World, cell: Vec3i, key: ItemKey, ports: Map<Vec3i, MachinePort>): Sink? {

		val port = ports[cell]
		if (port != null) {

			val buffer = port.buffer() ?: return null
			if (!buffer.accepts(key) || buffer.free() <= 0L) return null

			return Sink { itemKey, units -> buffer.insert(itemKey, units) }

		}

		val container = containerAt(world, cell) ?: return null
		if (!container.hasRoomFor(key)) return null

		return Sink { itemKey, units -> container.pushItem(itemKey, units) }

	}

	/** The live inventory of a vanilla container at [cell]. Crafting tables have none, so they fall out. */
	private fun containerAt(world: World, cell: Vec3i): Inventory? =
		(world.getBlockAt(cell.x, cell.y, cell.z).state as? Container)?.inventory

	/////////////////////
	///// VANILLA IMPORTS
	/////////////////////

	/** Pulls into [buffer] from any container feeding a crafting table upstream of [portCell]. */
	private fun fill(world: World, portCell: Vec3i, buffer: MachineBuffer) {

		if (buffer.free() <= 0L) return

		for (direction in CARTESIAN_FACES) {

			val conduitCell = portCell + direction.vec3i

			// only a conduit actually pointing into this port can be feeding it
			if (!outputsThrough(world, conduitCell, direction.oppositeFace)) continue

			val tableCell = traceImport(world, conduitCell, HashSet(), 0) ?: continue
			pullInto(world, tableCell, buffer)

		}

	}

	/** Whether the conduit at [cell] passes items out through its [face] side. */
	private fun outputsThrough(world: World, cell: Vec3i, face: BlockFace): Boolean {

		val block = world.getBlockAt(cell.x, cell.y, cell.z)

		return when (block.anionBlock) {

			null -> false
			AnionBlocks.COPPER_CONDUIT_JUNCTION -> true
			is AnionDirectionalBlock -> block.anionFacing == face

			else -> false

		}

	}

	/** Walks a run backwards from [cell] to the crafting table importing into it, if there is one. */
	private fun traceImport(world: World, cell: Vec3i, visited: MutableSet<Vec3i>, depth: Int): Vec3i? {

		if (depth > MAX_ROUTE_LENGTH) return null
		if (!visited.add(cell)) return null

		val block = world.getBlockAt(cell.x, cell.y, cell.z)

		val inputs = when (block.anionBlock) {

			null -> return null
			AnionBlocks.COPPER_CONDUIT_JUNCTION -> CARTESIAN_FACES
			is AnionDirectionalBlock -> listOf((block.anionFacing ?: return null).oppositeFace)

			else -> return null

		}

		for (input in inputs) {

			val previous = cell + input.vec3i

			if (world.getBlockAt(previous.x, previous.y, previous.z).type == Material.CRAFTING_TABLE) return previous

			// only follow a neighbour that actually feeds this cell
			if (!outputsThrough(world, previous, input.oppositeFace)) continue

			traceImport(world, previous, visited, depth + 1)?.let { return it }

		}

		return null

	}

	/** Draws whatever the containers around [tableCell] hold into [buffer]. */
	private fun pullInto(world: World, tableCell: Vec3i, buffer: MachineBuffer) {

		for (direction in CARTESIAN_FACES) {

			if (buffer.free() <= 0L) return

			val container = containerAt(world, tableCell + direction.vec3i) ?: continue

			for (key in container.itemKeys()) {

				if (!buffer.accepts(key)) continue

				val room = minOf(THROUGHPUT, buffer.free())
				if (room <= 0L) return

				val drawn = container.drawItem(key, room)
				if (drawn <= 0L) continue

				val accepted = buffer.insert(key, drawn)
				if (accepted < drawn) container.pushItem(key, drawn - accepted) // never drop what did not fit

			}

		}

	}

	/** Moves up to [THROUGHPUT] of [key] across, returning anything the far end could not take. */
	private fun handOff(source: MachineBuffer, destination: Sink, key: ItemKey) {

		val taken = source.extract(key, THROUGHPUT)
		if (taken <= 0L) return

		val accepted = destination.push(key, taken)
		if (accepted < taken) source.insert(key, taken - accepted) // never drop what did not fit

	}


}
