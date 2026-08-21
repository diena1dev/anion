package dev.diena.anion.features.transport

import dev.diena.anion.extensions.CARTESIAN_FACES
import dev.diena.anion.extensions.anionAxis
import dev.diena.anion.extensions.anionBlock
import dev.diena.anion.extensions.hasRoomFor
import dev.diena.anion.extensions.plus
import dev.diena.anion.extensions.pushItem
import dev.diena.anion.extensions.vec3i
import dev.diena.anion.features.custom.ItemKey
import dev.diena.anion.features.machine.Machine
import dev.diena.anion.features.machine.component.MachinePort
import dev.diena.anion.features.machine.machine_types.PortedMachine
import net.minecraft.core.Vec3i
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Container
import org.bukkit.inventory.Inventory

/**
 * Runs a transport pass and finds routes across one. What each block actually does lives on the block:
 * see [AnionTransportComponent].
 *
 * **Components drive, ports do not.** A [MachinePort] exists only to bridge to an internal buffer, so
 * transport never iterates ports — it iterates its own blocks and looks a port up when one happens to
 * be on the other side. The item adapter is the chute; a pump on a valve and a connector on a conduit
 * are the same idea for fluid and power, when those land.
 *
 * Drop-offs: a bus port, or any vanilla container on an open side. Failing both, a component may spill
 * — an item pipe with nothing in front of one of its ends drops its contents on the floor.
 *
 * TODO: routes are re-walked from scratch every pass. Once networks get large this wants a cached
 *       graph rebuilt off block events, on top of the index rather than instead of it.
 * TODO: items only. A component that holds its contents rather than passing them straight through — a
 *       gas pipe filling up and flowing on — needs per-cell state, which a singleton AnionBlock cannot
 *       carry. That wants an instance layer like Machine.activeMachines, keyed by cell or by network.
 */
object AnionTransport {

	/** blocks walked before a route is abandoned */
	private const val MAX_ROUTE_LENGTH = 64

	/** items one handoff moves */
	internal const val THROUGHPUT = 64L

	/** One transport pass across every loaded world. Main thread — this reads and writes the world. */
	// dispatch only: resolve the component and hand it a pass. no block ever gets named here.
	fun tick() {

		for (world in Bukkit.getWorlds()) {

			val components = AnionTransportIndex.cellsIn(world)
			if (components.isEmpty()) continue

			val ports = portsIn(world)
			val budget = Budget()

			for (cell in components.toList()) {

				val block = world.getBlockAt(cell.x, cell.y, cell.z)

				// the index is a hint: a cell that no longer holds a component drops out on read
				val component = AnionTransportComponents.at(block)
				if (component == null) {
					AnionTransportIndex.unregister(block)
					continue
				}

				component.drive(TransportPass(world, cell, ports, budget))

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
	// a spill is the last resort. the whole network is searched for somewhere that will actually take
	// the items before any open end is allowed to have them, so a pipe left open on one branch of a
	// junction never beats a chest on the other.
	internal fun findSink(

		world: World,
		cell: Vec3i,
		entryFace: BlockFace,
		key: ItemKey,
		ports: Map<Vec3i, MachinePort>,
		blocked: Set<Vec3i>,

	): Sink? {

		val spills = mutableListOf<Sink>()

		return search(world, cell, entryFace, key, ports, blocked, HashSet(), 0, spills)
			?: spills.firstOrNull()

	}

	/** One step of [findSink], collecting any open end it passes into [spills]. */
	// depth-first with a shared visited set: it finds a path rather than the best one, which is all a
	// pipe run needs. the visited set is also what stops a junction loop spinning forever.
	private fun search(

		world: World,
		cell: Vec3i,
		entryFace: BlockFace,
		key: ItemKey,
		ports: Map<Vec3i, MachinePort>,
		blocked: Set<Vec3i>,
		visited: MutableSet<Vec3i>,
		depth: Int,
		spills: MutableList<Sink>,

	): Sink? {

		if (depth > MAX_ROUTE_LENGTH) return null
		if (cell in blocked) return null // where the items came from is not somewhere to put them

		// this cell may be the drop-off itself
		sinkAt(world, cell, key, ports)?.let { return it }

		// otherwise it has to be something that carries
		if (!visited.add(cell)) return null

		val block = world.getBlockAt(cell.x, cell.y, cell.z)
		val component = AnionTransportComponents.at(block) ?: return null
		val exits = component.exitsFor(block, entryFace) ?: return null

		for (exit in exits) {

			search(world, cell + exit.vec3i, exit.oppositeFace, key, ports, blocked, visited, depth + 1, spills)
				?.let { return it }

		}

		// remembered, not returned: the search carries on in case a real destination exists elsewhere
		if (spills.isEmpty()) {

			for (exit in exits) {

				val spill = component.spillAt(block, exit) ?: continue

				spills += spill
				break

			}

		}

		return null

	}

	/** Where a carrier at [cell] entered through [entryFace] can pass items on to, or null if it cannot. */
	private fun exitsOf(world: World, cell: Vec3i, entryFace: BlockFace): List<BlockFace>? {

		val block = world.getBlockAt(cell.x, cell.y, cell.z)

		return AnionTransportComponents.at(block)?.exitsFor(block, entryFace)

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
	 * ones where a side you expected to matter does not — a pipe lying on the wrong axis, or a chest
	 * that has simply filled up.
	 */
	fun describe(world: World, cell: Vec3i): List<String> {

		val lines = mutableListOf<String>()
		val block = world.getBlockAt(cell.x, cell.y, cell.z)
		val ports = portsIn(world)

		val axis = block.anionAxis
		val indexed = cell in AnionTransportIndex.cellsIn(world)

		lines += "$cell ${nameOf(block)}${axis?.let { " axis=$it" } ?: ""} indexed=$indexed component=${AnionTransportComponents.at(block) != null}"
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

		AnionTransportComponents.at(block)?.let { return "$name ${it.describe(block)}" }

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

			val component = AnionTransportComponents.at(block)
			val exits = component?.exitsFor(block, face)
			if (component == null || exits == null) {
				lines += "   $cell $name will not carry in through $face [DEAD END]"
				return
			}

			val spilling = exits.filter { component.spillAt(block, it) != null }
			val open = if (spilling.isEmpty()) "" else " [OPEN END — spills out ${spilling.joinToString(",")}]"

			lines += "   $cell $name exits ${exits.joinToString(",")}$open"

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
	internal fun containerAt(world: World, cell: Vec3i): Inventory? =
		(world.getBlockAt(cell.x, cell.y, cell.z).state as? Container)?.inventory

}
