package dev.diena.anion.features.transport

import dev.diena.anion.extensions.CARTESIAN_FACES
import dev.diena.anion.extensions.anionAxis
import dev.diena.anion.extensions.anionBlock
import dev.diena.anion.extensions.axis
import dev.diena.anion.extensions.faces
import dev.diena.anion.extensions.drawItem
import dev.diena.anion.extensions.hasRoomFor
import dev.diena.anion.extensions.itemKeys
import dev.diena.anion.extensions.plus
import dev.diena.anion.extensions.pushItem
import dev.diena.anion.extensions.vec3i
import dev.diena.anion.features.custom.ItemKey
import dev.diena.anion.features.custom.blocks.AnionBlocks
import dev.diena.anion.features.machine.Machine
import dev.diena.anion.features.machine.component.MachineBuffer
import dev.diena.anion.features.machine.component.MachinePort
import dev.diena.anion.features.machine.machine_types.PortedMachine
import net.minecraft.core.Vec3i
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
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
 * Carriers: the pipe and the junction. A pipe carries along the axis it was laid on, in at one end and
 * out at the other, either way round — turning a corner is what the junction is for. Direction comes
 * from where the items entered rather than from the block, so a run reads straight off the world with
 * nothing to configure and nothing to build backwards.
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
	private class Sink(

		/** the machine buffer behind this, or null for a vanilla container */
		val buffer: MachineBuffer?,
		private val accept: (key: ItemKey, units: Long) -> Long,

	) {

		/** puts up to [units] of [key] in, and returns how many landed */
		fun push(key: ItemKey, units: Long): Long = accept(key, units)

	}

	/**
	 * What each machine buffer has left to move this pass.
	 *
	 * Without this every driver got its own THROUGHPUT, so seven crafting tables feeding one machine
	 * simply moved seven times as much. Ports are supposed to raise throughput, but the ceiling has to
	 * come from the buffer being fed rather than from how many things happen to be pushing at it.
	 */
	private class Budget {

		private val remaining = HashMap<MachineBuffer, Long>()

		fun left(buffer: MachineBuffer?): Long =
			if (buffer == null) Long.MAX_VALUE // a vanilla container is limited by its own slots
			else remaining.getOrPut(buffer) { buffer.transferLimit() }

		fun spend(buffer: MachineBuffer?, units: Long) {
			if (buffer == null || units <= 0L) return
			remaining[buffer] = (left(buffer) - units).coerceAtLeast(0L)
		}

	}

	/** One transport pass across every loaded world. Main thread — this reads and writes the world. */
	fun tick() {

		for (world in Bukkit.getWorlds()) {

			val components = AnionTransportIndex.cellsIn(world)
			if (components.isEmpty()) continue

			val ports = portsIn(world)
			val budget = Budget()

			for (cell in components.toList()) {

				val block = world.getBlockAt(cell.x, cell.y, cell.z)

				// the index is a hint: a cell that no longer holds a component drops out on read
				if (!AnionTransportIndex.isComponent(block)) {
					AnionTransportIndex.unregister(block)
					continue
				}

				// pipes and the junction only carry, so there is nothing to drive for them
				when {
					block.type == Material.CRAFTING_TABLE -> driveTable(world, cell, ports, budget)
					block.anionBlock === AnionBlocks.ITEM_CHUTE -> driveChute(world, cell, ports, budget)
				}

			}

		}

	}

	/////////////
	///// DRIVERS
	/////////////

	/** Exports the buffer of any bus port touching the chute at [cell] out of any of its other sides. */
	// a chute has no front. the name suggests one, but making it directional only ever created a way to
	// build it backwards, so it takes the port on whichever side has one and pushes out of the rest.
	private fun driveChute(world: World, cell: Vec3i, ports: Map<Vec3i, MachinePort>, budget: Budget) {

		for (portFace in CARTESIAN_FACES) {

			val portCell = cell + portFace.vec3i
			val port = ports[portCell] ?: continue
			val buffer = port.buffer() ?: continue
			if (buffer.contents().isEmpty()) continue

			// never let a run curl back into the chute or the port it is draining
			val blocked = setOf(cell, portCell)

			// snapshot: handing off mutates the buffer we are reading
			for (resource in buffer.contents().keys.toList()) {

				val key = resource as? ItemKey ?: continue

				for (exit in CARTESIAN_FACES) {

					if (exit == portFace) continue // straight back into the machine it came from

					val sink = findSink(world, cell + exit.vec3i, exit.oppositeFace, key, ports, blocked, HashSet(), 0)
						?: continue

					// both ends of the move are rationed, so neither a busy source nor a popular
					// destination can be worked harder than its own ports allow
					val allowance = minOf(THROUGHPUT, budget.left(buffer), budget.left(sink.buffer))
					if (allowance <= 0L) break

					val taken = buffer.extract(key, allowance)
					if (taken <= 0L) continue

					val accepted = sink.push(key, taken)
					if (accepted < taken) buffer.insert(key, taken - accepted) // never drop what did not fit

					budget.spend(buffer, accepted)
					budget.spend(sink.buffer, accepted)

					break

				}

			}

		}

	}

	/** Imports from any vanilla container touching the crafting table at [cell]. */
	private fun driveTable(world: World, cell: Vec3i, ports: Map<Vec3i, MachinePort>, budget: Budget) {

		val sourceCells = CARTESIAN_FACES.map { cell + it.vec3i }.filter { containerAt(world, it) != null }
		if (sourceCells.isEmpty()) return

		// the table itself and the chests it pulls from are never valid drop-offs
		val blocked = (sourceCells + cell).toSet()

		for (sourceCell in sourceCells) {

			val container = containerAt(world, sourceCell) ?: continue

			for (key in container.itemKeys()) {

				for (direction in CARTESIAN_FACES) {

					val target = cell + direction.vec3i
					if (target in blocked) continue

					val sink = findSink(world, target, direction.oppositeFace, key, ports, blocked, HashSet(), 0)
						?: continue

					// the chest has no rate of its own, so the destination's limit is the whole ration
					val allowance = minOf(THROUGHPUT, budget.left(sink.buffer))
					if (allowance <= 0L) break

					val taken = container.drawItem(key, allowance)
					if (taken <= 0L) continue

					val accepted = sink.push(key, taken)
					if (accepted < taken) container.pushItem(key, taken - accepted) // never drop what did not fit

					budget.spend(sink.buffer, accepted)

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
		blocked: Set<Vec3i>,
		visited: MutableSet<Vec3i>,
		depth: Int,

	): Sink? {

		if (depth > MAX_ROUTE_LENGTH) return null
		if (cell in blocked) return null // where the items came from is not somewhere to put them

		// this cell may be the drop-off itself
		sinkAt(world, cell, key, ports)?.let { return it }

		// otherwise it has to be something that carries
		if (!visited.add(cell)) return null
		val exits = exitsOf(world, cell, entryFace) ?: return null

		for (exit in exits) {

			findSink(world, cell + exit.vec3i, exit.oppositeFace, key, ports, blocked, visited, depth + 1)
				?.let { return it }

		}

		return null

	}

	/** Where a carrier at [cell] entered through [entryFace] can pass items on to, or null if it cannot. */
	private fun exitsOf(world: World, cell: Vec3i, entryFace: BlockFace): List<BlockFace>? {

		val block = world.getBlockAt(cell.x, cell.y, cell.z)

		return when (block.anionBlock) {

			// both take items in on any face and pass them out of every other one
			AnionBlocks.ITEM_PIPE_JUNCTION,
			AnionBlocks.ITEM_CHUTE -> CARTESIAN_FACES.filter { it != entryFace }

			// a length of tube: in one end, out the other, either way round
			AnionBlocks.ITEM_PIPE -> {

				val axis = block.anionAxis ?: return null
				if (entryFace.axis != axis) return null // entered through a side rather than an end

				listOf(entryFace.oppositeFace)

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

			return Sink(buffer) { itemKey, units -> buffer.insert(itemKey, units) }

		}

		val container = containerAt(world, cell) ?: return null
		if (!container.hasRoomFor(key)) return null

		return Sink(null) { itemKey, units -> container.pushItem(itemKey, units) }

	}

	/////////////////
	///// DIAGNOSTICS
	/////////////////

	/**
	 * What transport sees at [cell]: what it is, what is on each of its six sides and the state of it,
	 * and where a run leaving it actually stops.
	 *
	 * Every side is listed whether or not it is useful, because the failures worth debugging are the
	 * ones where a side you expected to matter does not — a pipe pointing the wrong way, or a chest
	 * that has simply filled up.
	 */
	fun describe(world: World, cell: Vec3i): List<String> {

		val lines = mutableListOf<String>()
		val block = world.getBlockAt(cell.x, cell.y, cell.z)
		val ports = portsIn(world)

		val axis = block.anionAxis
		val indexed = cell in AnionTransportIndex.cellsIn(world)

		lines += "$cell ${nameOf(block)}${axis?.let { " axis=$it" } ?: ""} indexed=$indexed driver=${AnionTransportIndex.isComponent(block)}"
		ports[cell]?.let { lines += "  this cell is a bus port -> ${it.bufferKey ?: "unbound"}" }

		// every side, so a missing one is visible rather than silently skipped
		for (side in CARTESIAN_FACES) {
			lines += "  $side ${describeNeighbour(world, cell + side.vec3i, ports)}"
		}

		for (side in CARTESIAN_FACES) {

			val target = cell + side.vec3i

			// a side worth tracing is one a run could actually leave through
			val leads = ports[target] != null ||
				containerAt(world, target) != null ||
				exitsOf(world, target, side.oppositeFace) != null

			if (!leads) continue

			lines += "  out $side:"
			traceLine(world, target, side.oppositeFace, ports, lines)

		}

		return lines

	}

	/** One phrase describing whatever is at [cell] and whether it can take anything. */
	private fun describeNeighbour(world: World, cell: Vec3i, ports: Map<Vec3i, MachinePort>): String {

		val block = world.getBlockAt(cell.x, cell.y, cell.z)
		val name = nameOf(block)

		ports[cell]?.let { port ->
			val buffer = port.buffer()
				?: return "$name BUS PORT -> ${port.bufferKey ?: "unbound"} (no buffer: machine broken or unbound)"

			return "$name BUS PORT -> ${port.bufferKey} ${buffer.used()}/${buffer.capacity()}"
		}

		containerAt(world, cell)?.let { container ->
			val filled = container.contents.count { it != null && !it.type.isAir }
			val full = container.firstEmpty() == -1
			return "$name CONTAINER $filled/${container.size} slots${if (full) " [FULL — nothing more fits in an empty slot]" else ""}"
		}

		val blockAxis = block.anionAxis
		if (blockAxis != null) return "$name axis=$blockAxis (takes items in through ${blockAxis.faces.joinToString(" or ")})"

		if (block.anionBlock === AnionBlocks.ITEM_PIPE_JUNCTION) return "$name (any side in, any other out)"

		return name

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
			val name = nameOf(block)

			ports[cell]?.let {
				lines += "   $cell ${describeNeighbour(world, cell, ports)} [END]"
				return
			}

			if (containerAt(world, cell) != null) {
				lines += "   $cell ${describeNeighbour(world, cell, ports)} [END]"
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

	private fun nameOf(block: Block): String = block.anionBlock?.namespacedKey?.key ?: block.type.key.key

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
