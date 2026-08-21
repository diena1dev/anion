package dev.diena.anion.features.transport

import dev.diena.anion.extensions.drawItem
import dev.diena.anion.extensions.plus
import dev.diena.anion.extensions.pushItem
import dev.diena.anion.extensions.vec3i
import dev.diena.anion.features.custom.AnionResource
import dev.diena.anion.features.custom.ItemKey
import dev.diena.anion.features.machine.Machine
import dev.diena.anion.features.machine.component.MachineBuffer
import dev.diena.anion.features.machine.component.MachinePort
import net.minecraft.core.Vec3i
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.inventory.Inventory

/**
 * A block transport knows how to use. Implemented by the block itself, so [AnionTransport] never asks
 * which block it is holding — it resolves the component and calls the hook, the way AnionItemDispatcher
 * resolves an item and calls onPlayerInteract.
 *
 * A carrier implements [exitsFor] and stops there. A driver also overrides [drive], which is where the
 * work of actually moving something happens.
 *
 * Nothing here says "item". A gas pipe that buffers its contents, or a conduit that carries charge,
 * implements the same three functions and needs no change to the pass that calls them.
 */
interface AnionTransportComponent {

	/** Where something entering [block] through [entryFace] can go next, or null if it will not carry. */
	fun exitsFor(block: Block, entryFace: BlockFace): List<BlockFace>?

	/**
	 * Whether this component will carry [resource] at all. Geometry is [exitsFor]'s job; this is the
	 * policy half, so a filter can refuse an item a pipe would happily have taken.
	 */
	fun accepts(block: Block, resource: AnionResource): Boolean = true

	/** Moves whatever this component is responsible for moving. Carriers do nothing. */
	fun drive(pass: TransportPass) {}

	/**
	 * Where this component lets go of what it could not pass on through [exit], or null to hold onto
	 * it. An item pipe drops its contents into the world; a gas pipe would vent.
	 *
	 * Only consulted once the whole network has been searched and nothing on it had room, so a full
	 * chest downstream never causes one.
	 */
	fun spillAt(block: Block, exit: BlockFace): Sink? = null

	/** How this component behaves, for `/transport debug`. The caller prefixes the block's name. */
	fun describe(block: Block): String

}

/////////////////
///// PASS
/////////////////

/**
 * One driver's view of a transport pass: where it is, what is around it, and what it is still allowed
 * to move. A component gets this rather than the world, so the rationing in [route] cannot be skipped.
 */
class TransportPass internal constructor(

	val world: World,
	val cell: Vec3i,
	private val ports: Map<Vec3i, MachinePort>,
	private val budget: Budget,

) {

	/** The bus port at [cell], or null when nothing there bridges to a buffer. */
	fun portAt(cell: Vec3i): MachinePort? = ports[cell]

	/** The live inventory of a vanilla container at [cell]. Crafting tables have none. */
	fun containerAt(cell: Vec3i): Inventory? = AnionTransport.containerAt(world, cell)

	/**
	 * Moves [key] out of [source] through the first of [exits] that reaches somewhere with room, and
	 * returns whether anything moved. Cells in [blocked] are never a destination.
	 */
	fun route(source: Source, key: ItemKey, exits: List<BlockFace>, blocked: Set<Vec3i>): Boolean {

		for (exit in exits) {

			val target = cell + exit.vec3i
			if (target in blocked) continue

			val sink = AnionTransport.findSink(world, target, exit.oppositeFace, key, ports, blocked) ?: continue

			// both ends of the move are rationed, so neither a busy source nor a popular destination
			// can be worked harder than its own ports allow
			val allowance = minOf(AnionTransport.THROUGHPUT, budget.left(source.buffer), budget.left(sink.buffer))
			if (allowance <= 0L) return false

			val taken = source.draw(key, allowance)
			if (taken <= 0L) continue

			val accepted = sink.push(key, taken)
			if (accepted < taken) source.putBack(key, taken - accepted) // never drop what did not fit

			budget.spend(source.buffer, accepted)
			budget.spend(sink.buffer, accepted)

			return true

		}

		return false

	}

}

/////////////////
///// ENDPOINTS
/////////////////

/** Somewhere a run can take items from. */
class Source(

	/** the machine buffer behind this, or null for a vanilla container */
	val buffer: MachineBuffer?,
	private val take: (key: ItemKey, units: Long) -> Long,
	private val giveBack: (key: ItemKey, units: Long) -> Unit,

) {

	/** takes up to [units] of [key] out, and returns how many came */
	fun draw(key: ItemKey, units: Long): Long = take(key, units)

	/** returns [units] of [key] that the destination would not take */
	fun putBack(key: ItemKey, units: Long) = giveBack(key, units)

	companion object {

		fun of(buffer: MachineBuffer) = Source(
			buffer,
			{ key, units -> buffer.extract(key, units) },
			{ key, units -> buffer.insert(key, units) },
		)

		fun of(container: Inventory) = Source(
			null,
			{ key, units -> container.drawItem(key, units) },
			{ key, units -> container.pushItem(key, units) },
		)

	}

}

/** Somewhere a run can put items down. */
class Sink(

	/** the machine buffer behind this, or null for a vanilla container */
	val buffer: MachineBuffer?,
	private val accept: (key: ItemKey, units: Long) -> Long,

) {

	/** puts up to [units] of [key] in, and returns how many landed */
	fun push(key: ItemKey, units: Long): Long = accept(key, units)

}

/**
 * What is left to move this pass, at both levels that ration it.
 *
 * Without this every driver got its own throughput, so seven crafting tables feeding one machine simply
 * moved seven times as much. Ports are supposed to raise throughput, but the ceiling has to come from
 * the thing being fed rather than from how many happen to be pushing at it.
 *
 * Two levels because the port count decides a buffer's rate and nothing else may: the machine's own
 * ceiling is shared across all of its buffers, so a wall of ports spread over three of them runs into
 * exactly the same wall as a wall of ports on one.
 */
class Budget internal constructor() {

	private val perBuffer = HashMap<MachineBuffer, Long>()
	private val perMachine = HashMap<Machine, Long>()

	fun left(buffer: MachineBuffer?): Long {

		if (buffer == null) return Long.MAX_VALUE // a vanilla container is limited by its own slots

		val bufferLeft = perBuffer.getOrPut(buffer) { buffer.transferLimit() }
		val machine = buffer.owner ?: return bufferLeft

		return minOf(bufferLeft, perMachine.getOrPut(machine) { machine.transferCeiling })

	}

	fun spend(buffer: MachineBuffer?, units: Long) {

		if (buffer == null || units <= 0L) return

		perBuffer[buffer] = (perBuffer.getOrPut(buffer) { buffer.transferLimit() } - units).coerceAtLeast(0L)

		val machine = buffer.owner ?: return
		perMachine[machine] = (perMachine.getOrPut(machine) { machine.transferCeiling } - units).coerceAtLeast(0L)

	}

}
