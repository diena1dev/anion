package dev.diena.anion.features.transport

import dev.diena.anion.extensions.CARTESIAN_FACES
import dev.diena.anion.extensions.anionBlock
import dev.diena.anion.extensions.anionFacing
import dev.diena.anion.extensions.drawItem
import dev.diena.anion.extensions.hasRoomFor
import dev.diena.anion.extensions.itemKeys
import dev.diena.anion.extensions.minus
import dev.diena.anion.extensions.plus
import dev.diena.anion.extensions.pushItem
import dev.diena.anion.extensions.vec3i
import dev.diena.anion.features.custom.ItemKey
import dev.diena.anion.features.custom.blocks.AnionBlocks
import dev.diena.anion.features.machine.Machine
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
 * Moves items along pipe runs.
 *
 * **Components drive, ports do not.** A [MachinePort] exists only to bridge to an internal buffer, so
 * transport never iterates ports — it iterates its own blocks and looks a port up when one happens to
 * be on the other side. The item adapter is the chute; a pump on a valve and a connector on a conduit
 * are the same idea for fluid and power, when those land.
 *
 * Drivers:
 * - **chute** — exports the buffer of the bus port behind it into whatever is in front
 * - **crafting table** — imports from any vanilla container touching it
 *
 * Carriers: pipes and the junction. Flow is a pipe's own facing, so a run only takes items in through
 * its back face and only passes them out of its front — turning a corner is what the junction is for.
 * That makes the network a directed graph read straight off the world, with nothing to configure.
 *
 * Drop-offs: a bus port, or any vanilla container on an open side.
 *
 * TODO: routes are re-walked from scratch every pass. Once networks get large this wants a cached
 *       graph rebuilt off block events, on top of the index rather than instead of it.
 * TODO: items only. Gas, fluid and energy get their own adapters and pipe families later.
 */
object AnionTransport {

	/** blocks walked before a route is abandoned */
	private const val MAX_ROUTE_LENGTH = 64

	/** items a single driver hands off per pass */
	private const val THROUGHPUT = 64L

	/** somewhere a run can put items down */
	private fun interface Sink {

		/** puts up to [units] of [key] in, and returns how many landed */
		fun push(key: ItemKey, units: Long): Long

	}

	/** One transport pass across every loaded world. Main thread — this reads and writes the world. */
	fun tick() {

		for (world in Bukkit.getWorlds()) {

			val components = AnionTransportIndex.cellsIn(world)
			if (components.isEmpty()) continue

			val ports = portsIn(world)

			for (cell in components.toList()) {

				val block = world.getBlockAt(cell.x, cell.y, cell.z)

				// the index is a hint: a cell that no longer holds a component drops out on read
				if (!AnionTransportIndex.isComponent(block)) {
					AnionTransportIndex.unregister(block)
					continue
				}

				// pipes and the junction only carry, so there is nothing to drive for them
				when {
					block.type == Material.CRAFTING_TABLE -> driveTable(world, cell, ports)
					block.anionBlock === AnionBlocks.COPPER_CHUTE -> driveChute(world, cell, ports)
				}

			}

		}

	}

	/////////////
	///// DRIVERS
	/////////////

	/** Exports the buffer of the bus port behind the chute at [cell] into whatever is in front of it. */
	private fun driveChute(world: World, cell: Vec3i, ports: Map<Vec3i, MachinePort>) {

		val facing = world.getBlockAt(cell.x, cell.y, cell.z).anionFacing ?: return

		// a chute with no port behind it is just another length of pipe
		val port = ports[cell - facing.vec3i] ?: return
		val buffer = port.buffer() ?: return
		if (buffer.contents().isEmpty()) return

		val ahead = cell + facing.vec3i

		// snapshot: handing off mutates the buffer we are reading
		for (resource in buffer.contents().keys.toList()) {

			val key = resource as? ItemKey ?: continue
			val sink = findSink(world, ahead, facing.oppositeFace, key, ports, cell, HashSet(), 0) ?: continue

			val taken = buffer.extract(key, THROUGHPUT)
			if (taken <= 0L) continue

			val accepted = sink.push(key, taken)
			if (accepted < taken) buffer.insert(key, taken - accepted) // never drop what did not fit

		}

	}

	/** Imports from any vanilla container touching the crafting table at [cell]. */
	private fun driveTable(world: World, cell: Vec3i, ports: Map<Vec3i, MachinePort>) {

		val sources = CARTESIAN_FACES.mapNotNull { containerAt(world, cell + it.vec3i) }
		if (sources.isEmpty()) return

		for (container in sources) {
			for (key in container.itemKeys()) {

				for (direction in CARTESIAN_FACES) {

					val target = cell + direction.vec3i

					// a table imports into the network; it does not shuffle between neighbouring chests
					if (containerAt(world, target) != null) continue

					val sink = findSink(world, target, direction.oppositeFace, key, ports, cell, HashSet(), 0)
						?: continue

					val taken = container.drawItem(key, THROUGHPUT)
					if (taken <= 0L) continue

					val accepted = sink.push(key, taken)
					if (accepted < taken) container.pushItem(key, taken - accepted) // never drop what did not fit

					break

				}

			}
		}

	}

	/////////////
	///// ROUTING
	/////////////

	/**
	 * Follows the run at [cell], entered through its [entryFace], to the first place that will take
	 * [key]. Returns null when it dead-ends or nothing on it has room.
	 */
	// depth-first with a shared visited set: it finds a path rather than the best one, which is all a
	// pipe run needs. the visited set is also what stops a junction loop spinning forever.
	private fun findSink(

		world: World,
		cell: Vec3i,
		entryFace: BlockFace,
		key: ItemKey,
		ports: Map<Vec3i, MachinePort>,
		originCell: Vec3i,
		visited: MutableSet<Vec3i>,
		depth: Int,

	): Sink? {

		if (depth > MAX_ROUTE_LENGTH) return null

		// this cell may be the drop-off itself
		sinkAt(world, cell, key, ports)?.let { return it }

		// otherwise it has to be something that carries
		if (!visited.add(cell)) return null
		val exits = exitsOf(world, cell, entryFace) ?: return null

		for (exit in exits) {

			val next = cell + exit.vec3i
			if (next == originCell) continue // straight back where it came from is not transport

			findSink(world, next, exit.oppositeFace, key, ports, originCell, visited, depth + 1)?.let { return it }

		}

		return null

	}

	/** Where a carrier at [cell] entered through [entryFace] can pass items on to, or null if it cannot. */
	private fun exitsOf(world: World, cell: Vec3i, entryFace: BlockFace): List<BlockFace>? {

		val block = world.getBlockAt(cell.x, cell.y, cell.z)

		return when (block.anionBlock) {

			AnionBlocks.COPPER_PIPE_JUNCTION -> CARTESIAN_FACES.filter { it != entryFace }

			// a chute carries like any straight run when it is not the one driving
			AnionBlocks.COPPER_PIPE,
			AnionBlocks.COPPER_PIPE_VERTICAL,
			AnionBlocks.COPPER_CHUTE -> {

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

	/////////////////
	///// DIAGNOSTICS
	/////////////////

	/**
	 * One line per fact about the component at [cell] — what it is, which way it points, and where a
	 * run leaving it actually stops. A pipe's facing is invisible in world, so this is how you tell a
	 * north-facing pipe from a south-facing one without breaking it.
	 */
	fun describe(world: World, cell: Vec3i): List<String> {

		val lines = mutableListOf<String>()
		val block = world.getBlockAt(cell.x, cell.y, cell.z)
		val ports = portsIn(world)

		val name = block.anionBlock?.namespacedKey?.key ?: block.type.key.key
		val facing = block.anionFacing
		val indexed = cell in AnionTransportIndex.cellsIn(world)

		lines += "$cell $name facing=${facing ?: "-"} indexed=$indexed driver=${AnionTransportIndex.isComponent(block)}"

		ports[cell]?.let { lines += "  is a bus port -> ${it.bufferKey ?: "unbound"}" }

		when {

			block.type == Material.CRAFTING_TABLE -> {

				val touching = CARTESIAN_FACES.filter { containerAt(world, cell + it.vec3i) != null }
				lines += "  containers touching: ${touching.joinToString(" ").ifEmpty { "none" }}"

				for (direction in CARTESIAN_FACES) {
					val target = cell + direction.vec3i
					if (containerAt(world, target) != null) continue
					if (exitsOf(world, target, direction.oppositeFace) == null && ports[target] == null) continue

					lines += "  out $direction:"
					traceLine(world, target, direction.oppositeFace, ports, lines)
				}

			}

			block.anionBlock === AnionBlocks.COPPER_CHUTE && facing != null -> {

				val behind = cell - facing.vec3i
				val port = ports[behind]

				lines += "  behind $behind: ${port?.let { "bus port -> ${it.bufferKey ?: "unbound"}" } ?: "no bus port, so it only carries"}"
				port?.buffer()?.let { lines += "  buffer ${it.used()}/${it.capacity()}" }

				lines += "  ahead:"
				traceLine(world, cell + facing.vec3i, facing.oppositeFace, ports, lines)

			}

			facing != null -> {
				lines += "  carries from ${facing.oppositeFace} to $facing"
				lines += "  ahead:"
				traceLine(world, cell + facing.vec3i, facing.oppositeFace, ports, lines)
			}

		}

		return lines

	}

	/** Follows single exits from [from] until something stops it, saying why at each step. */
	// junctions branch, so this reports the first exit only — enough to find the break in a straight run
	private fun traceLine(

		world: World,
		from: Vec3i,
		entryFace: BlockFace,
		ports: Map<Vec3i, MachinePort>,
		lines: MutableList<String>,

	) {

		var cell = from
		var face = entryFace

		repeat(MAX_ROUTE_LENGTH) {

			val block = world.getBlockAt(cell.x, cell.y, cell.z)
			val name = block.anionBlock?.namespacedKey?.key ?: block.type.key.key

			ports[cell]?.let {
				lines += "   $cell bus port -> ${it.bufferKey ?: "unbound"} [END]"
				return
			}

			if (containerAt(world, cell) != null) {
				lines += "   $cell $name container [END]"
				return
			}

			val exits = exitsOf(world, cell, face)
			if (exits == null) {
				lines += "   $cell $name will not carry in through $face [DEAD END]"
				return
			}

			lines += "   $cell $name exits ${exits.joinToString(",")}"

			val exit = exits.first()
			cell += exit.vec3i
			face = exit.oppositeFace

		}

		lines += "   ... gave up after $MAX_ROUTE_LENGTH"

	}

	/////////////
	///// LOOKUPS
	/////////////

	/** Every bus port in [world], by cell. A lookup only — transport never drives off this. */
	// built per pass rather than indexed, so it picks up ship-carried machines too: their cells move
	// every tick and are deliberately absent from MachineIndex.
	private fun portsIn(world: World): Map<Vec3i, MachinePort> {

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

	/** The live inventory of a vanilla container at [cell]. Crafting tables have none, so they fall out. */
	private fun containerAt(world: World, cell: Vec3i): Inventory? =
		(world.getBlockAt(cell.x, cell.y, cell.z).state as? Container)?.inventory

}
