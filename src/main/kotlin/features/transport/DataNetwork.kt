package dev.diena.anion.features.transport

import dev.diena.anion.extensions.CARTESIAN_FACES
import dev.diena.anion.extensions.plus
import dev.diena.anion.extensions.vec3i
import dev.diena.anion.features.machine.Machine
import dev.diena.anion.features.machine.component.MachinePort
import net.minecraft.core.Vec3i
import net.minecraft.server.level.ServerLevel
import org.bukkit.block.BlockFace

/**
 * Which machines are wired to which, over data lines. A mainframe reaches a machine when a run of
 * [AnionDataComponent] blocks joins one of its dataports to one of theirs.
 *
 * Separate from [AnionTransport] on purpose: nothing is being moved. This walks geometry on demand and
 * answers a question, rather than driving a pass, so it never appears in the item loop.
 */
object DataNetwork {

	/** cells walked before a network is abandoned */
	private const val MAX_NETWORK_SIZE = 4096

	/** Every other machine [machine] can reach over data lines from its own dataports. */
	fun reachableFrom(machine: Machine): Set<Machine> {

		val startCells = machine.portWorldCells(MachinePort.Kind.DATA)
		if (startCells.isEmpty()) return emptySet()

		val ports = dataPortsIn(machine.level)
		val world = machine.level.world

		val found = mutableSetOf<Machine>()
		val visited = HashSet<Vec3i>()
		val queue = ArrayDeque<Pair<Vec3i, BlockFace>>()

		// the dataports themselves are the run's ends, so the walk starts on whatever they touch
		for (cell in startCells) for (face in CARTESIAN_FACES) queue += (cell + face.vec3i) to face.oppositeFace

		while (queue.isNotEmpty()) {

			if (visited.size >= MAX_NETWORK_SIZE) break

			val (cell, entryFace) = queue.removeFirst()
			if (!visited.add(cell)) continue

			// a dataport is where a run stops, whoever it belongs to
			val holder = ports[cell]
			if (holder != null) {

				if (holder !== machine) found += holder
				continue

			}

			val block = world.getBlockAt(cell.x, cell.y, cell.z)
			val component = AnionTransportComponents.at(block) as? AnionDataComponent ?: continue
			val exits = component.exitsFor(block, entryFace) ?: continue

			for (exit in exits) queue += (cell + exit.vec3i) to exit.oppositeFace

		}

		return found

	}

	/** What a data line run leaving [machine] actually reaches, for `/mainframe debug`. */
	fun describe(machine: Machine): List<String> {

		val lines = mutableListOf<String>()

		val startCells = machine.portWorldCells(MachinePort.Kind.DATA)
		lines += "dataports=${startCells.size}"

		if (startCells.isEmpty()) {

			lines += "  no dataport in this machine's casing — nothing can be wired to it"
			return lines

		}

		val reached = reachableFrom(machine)
		if (reached.isEmpty()) lines += "  reaches nothing"

		for (other in reached) lines += "  ${other.namespacedKey.key} @ ${other.origin} intact=${other.intact}"

		return lines

	}

	/**
	 * Every data port in [level], by cell, and the machine behind it.
	 *
	 * Built per walk rather than indexed, the same way [AnionTransport] finds bus ports: a ship-carried
	 * machine's cells move every tick and are deliberately absent from MachineIndex.
	 */
	private fun dataPortsIn(level: ServerLevel): Map<Vec3i, Machine> {

		val ports = HashMap<Vec3i, Machine>()

		for (machine in Machine.activeMachines.values) {

			if (machine.level != level) continue
			if (!machine.intact) continue

			for (cell in machine.portWorldCells(MachinePort.Kind.DATA)) ports[cell] = machine

		}

		return ports

	}

}
