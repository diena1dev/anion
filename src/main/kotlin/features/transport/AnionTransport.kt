package dev.diena.anion.features.transport

import dev.diena.anion.extensions.CARTESIAN_FACES
import dev.diena.anion.extensions.anionBlock
import dev.diena.anion.extensions.anionFacing
import dev.diena.anion.extensions.plus
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
import org.bukkit.World
import org.bukkit.block.BlockFace

/**
 * Moves items between machine bus ports along conduit runs.
 *
 * Flow is the conduit's own facing: a straight conduit only takes items in through its back face and
 * only passes them out of its front, so turning a corner is what the junction is for. That makes the
 * network a directed graph read straight off the world, with nothing to configure and nothing to
 * cache — which is also why breaking a conduit needs no invalidation.
 *
 * TODO: routes are re-walked from scratch every pass. Once networks get large this wants a cached
 *       graph rebuilt off block events, the same way MachineIndex handles machine cells.
 * TODO: items only. Gas, fluid and energy get their own conduit families later.
 */
object AnionTransport {

	/** conduit blocks walked before a route is abandoned */
	private const val MAX_ROUTE_LENGTH = 64

	/** items a single port hands off per pass */
	private const val THROUGHPUT = 64L

	/** One transport pass across every loaded world. Main thread — this reads and writes the world. */
	fun tick() {

		for (world in Bukkit.getWorlds()) {

			val ports = busPortsIn(world)
			if (ports.size < 2) continue // nothing to move between

			for ((cell, port) in ports) {

				val buffer = port.buffer() ?: continue // broken machine, or an unbound port
				if (buffer.contents().isEmpty()) continue

				drain(world, cell, port, buffer, ports)

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
		sourcePort: MachinePort,
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

				val destination = route(world, conduitCell, direction.oppositeFace, key, ports, HashSet(), 0)
					?: continue

				if (destination === sourcePort) continue // a loop back into ourselves is not transport

				handOff(sourceBuffer, destination.buffer() ?: continue, key)

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
	 * Walks the conduit at [cell], entered through its [entryFace], to the first bus port that will
	 * take [key]. Returns null when the run dead-ends or nothing on it has room.
	 */
	// depth-first with a shared visited set: it finds a path rather than the best one, which is all a
	// conduit run needs. the visited set is also what stops a junction loop spinning forever.
	private fun route(

		world: World,
		cell: Vec3i,
		entryFace: BlockFace,
		key: ItemKey,
		ports: Map<Vec3i, MachinePort>,
		visited: MutableSet<Vec3i>,
		depth: Int,

	): MachinePort? {

		if (depth > MAX_ROUTE_LENGTH) return null
		if (!visited.add(cell)) return null

		val block = world.getBlockAt(cell.x, cell.y, cell.z)

		val exits = when (block.anionBlock) {

			null -> return null
			AnionBlocks.COPPER_CONDUIT_JUNCTION -> CARTESIAN_FACES.filter { it != entryFace }

			is AnionDirectionalBlock -> {

				val facing = block.anionFacing ?: return null
				if (entryFace != facing.oppositeFace) return null // entered against the flow

				listOf(facing)

			}

			else -> return null

		}

		for (exit in exits) {

			val next = cell + exit.vec3i

			val port = ports[next]
			if (port != null) {

				val buffer = port.buffer()
				if (buffer != null && buffer.accepts(key) && buffer.free() > 0L) return port

				continue // a port that will not take this ends the branch; conduits do not tunnel through machines

			}

			route(world, next, exit.oppositeFace, key, ports, visited, depth + 1)?.let { return it }

		}

		return null

	}

	/** Moves up to [THROUGHPUT] of [key] across, returning anything the far end could not take. */
	private fun handOff(source: MachineBuffer, destination: MachineBuffer, key: ItemKey) {

		val taken = source.extract(key, THROUGHPUT)
		if (taken <= 0L) return

		val accepted = destination.insert(key, taken)
		if (accepted < taken) source.insert(key, taken - accepted) // never drop what did not fit

	}

}
