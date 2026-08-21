package dev.diena.anion.features.transport

import dev.diena.anion.extensions.CARTESIAN_FACES
import dev.diena.anion.extensions.anionAxis
import dev.diena.anion.extensions.axis
import dev.diena.anion.extensions.faces
import dev.diena.anion.extensions.itemKeys
import dev.diena.anion.extensions.plus
import dev.diena.anion.extensions.vec3i
import dev.diena.anion.features.custom.AnionResource
import dev.diena.anion.features.custom.ItemKey
import dev.diena.anion.features.custom.blocks.AnionBlock
import dev.diena.anion.features.custom.blocks.AnionBlocks
import dev.diena.anion.features.custom.blocks.AnionPillarBlock
import net.kyori.adventure.text.Component
import org.bukkit.Axis
import org.bukkit.Instrument
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Container
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.Hopper
import org.bukkit.block.data.type.NoteBlock

/**
 * A length of tube. Carries along the axis it was laid on, in at one end and out at the other, either
 * way round — turning a corner is what the junction is for.
 */
class AnionItemPipeBlock(

	displayName: String,
	instrument: Instrument,
	notesByAxis: Map<Axis, Int>,
	styledDisplayName: Component = Component.text(displayName),

) : AnionPillarBlock(displayName, instrument, notesByAxis, styledDisplayName = styledDisplayName), AnionTransportComponent {

	override fun exitsFor(block: Block, entryFace: BlockFace): List<BlockFace>? {

		val axis = block.anionAxis ?: return null
		if (entryFace.axis != axis) return null // entered through a side rather than an end

		return listOf(entryFace.oppositeFace)

	}

	/** An end with nothing in front of it spills, which is what makes a pipe need capping. */
	override fun spillAt(block: Block, exit: BlockFace): Sink? {

		val ahead = block.getRelative(exit)
		if (!ahead.isEmpty) return null // any block at all caps the run, a junction included

		return Sink(null) { key, units -> spill(ahead, exit, key, units) }

	}

	override fun describe(block: Block): String {

		val axis = block.anionAxis ?: return "(no axis: not a placed pipe)"

		val open = axis.faces.filter { block.getRelative(it).isEmpty }
		val ends = if (open.isEmpty()) "" else " [OPEN at ${open.joinToString(" and ")} — spills]"

		return "axis=$axis (takes items in through ${axis.faces.joinToString(" or ")})$ends"

	}

	/** Drops [units] of [key] into [cell], pushed along [exit]. Always takes the lot. */
	private fun spill(cell: Block, exit: BlockFace, key: ItemKey, units: Long): Long {

		val location = cell.location.toCenterLocation()
		val maxStack = key.stack.maxStackSize.toLong().coerceAtLeast(1L)
		var left = units

		while (left > 0L) {

			val amount = minOf(left, maxStack).toInt()
			cell.world.dropItem(location, key.asItemStack(amount)).velocity = exit.direction.multiply(0.15)
			left -= amount

		}

		return units

	}

}

/** Takes items in on any face and passes them out of every other one. */
class AnionItemPipeJunctionBlock(

	displayName: String,
	instrument: Instrument,
	note: Int,
	styledDisplayName: Component = Component.text(displayName),

) : AnionBlock(displayName, instrument, note, styledDisplayName = styledDisplayName), AnionTransportComponent {

	override fun exitsFor(block: Block, entryFace: BlockFace): List<BlockFace> =
		CARTESIAN_FACES.filter { it != entryFace }

	override fun describe(block: Block): String = "(any side in, any other out)"

}

/**
 * The item adapter. Sits against a machine's bus port and drives what the port only provides access to,
 * draining that buffer out of every one of its other sides.
 *
 * No facing. The name suggests one, but making it directional only ever created a way to build it
 * backwards, so it takes the port on whichever side has one. With no port beside it, it carries like a
 * junction.
 */
class AnionItemChuteBlock(

	displayName: String,
	instrument: Instrument,
	note: Int,
	styledDisplayName: Component = Component.text(displayName),

) : AnionBlock(displayName, instrument, note, styledDisplayName = styledDisplayName), AnionTransportComponent {

	override fun exitsFor(block: Block, entryFace: BlockFace): List<BlockFace> =
		CARTESIAN_FACES.filter { it != entryFace }

	override fun describe(block: Block): String = "(drains the bus port beside it out of every other side)"

	override fun drive(pass: TransportPass) {

		for (portFace in CARTESIAN_FACES) {

			val portCell = pass.cell + portFace.vec3i
			val port = pass.portAt(portCell) ?: continue
			val buffer = port.buffer() ?: continue
			if (buffer.contents().isEmpty()) continue

			// never let a run curl back into the chute or the port it is draining
			val blocked = setOf(pass.cell, portCell)
			val exits = CARTESIAN_FACES.filter { it != portFace }
			val source = Source.of(buffer)

			// snapshot: handing off mutates the buffer we are reading
			for (resource in buffer.contents().keys.toList()) {

				val key = resource as? ItemKey ?: continue
				pass.route(source, key, exits, blocked)

			}

		}

	}

}

/**
 * The vanilla-inventory import adapter, bound to the crafting table. Pulls from any container touching
 * it and pushes into whatever the network offers.
 *
 * Asymmetric on purpose: a chest feeds the network through a table rather than being drained by any
 * pipe that happens to run past. It never carries, so a run cannot pass through one.
 */
object VanillaContainerImporter : AnionTransportComponent {

	override fun exitsFor(block: Block, entryFace: BlockFace): List<BlockFace>? = null

	override fun describe(block: Block): String = "(imports from any container touching it)"

	override fun drive(pass: TransportPass) {

		val sourceCells = CARTESIAN_FACES.map { pass.cell + it.vec3i }.filter { pass.containerAt(it) != null }
		if (sourceCells.isEmpty()) return

		// the table itself and the chests it pulls from are never valid drop-offs
		val blocked = (sourceCells + pass.cell).toSet()

		for (sourceCell in sourceCells) {

			val container = pass.containerAt(sourceCell) ?: continue
			val source = Source.of(container)

			for (key in container.itemKeys()) pass.route(source, key, CARTESIAN_FACES, blocked)

		}

	}

}

/**
 * The item filter, bound to the vanilla hopper. Carries out of the face it points, refusing anything
 * its filter list says it should.
 *
 * The list is the hopper's own inventory, which is the whole reason the filter is a hopper: an
 * AnionBlock is one instance shared by every placed copy and cannot hold a per-cell configuration,
 * while a hopper's contents are per-cell state vanilla already stores and persists.
 *
 * Redstone picks the mode, and vanilla stores that too — a powered hopper is `enabled=false`:
 * - **unpowered** — whitelist, carries only what is listed
 * - **powered** — blacklist, carries everything except what is listed
 *
 * An empty hopper is an unconfigured one and carries everything, in either mode. A hopper dropped into
 * a run mid-build should not silently kill the network.
 *
 * Items match the way they match everywhere else, by [ItemKey], so an enchanted tool is a different
 * thing from a plain one and has to be listed separately.
 */
object HopperFilter : AnionTransportComponent {

	override fun exitsFor(block: Block, entryFace: BlockFace): List<BlockFace>? {

		val facing = (block.blockData as? Hopper)?.facing ?: return null
		if (entryFace == facing) return null // that side is the output, nothing comes in through it

		return listOf(facing)

	}

	override fun accepts(block: Block, resource: AnionResource): Boolean {

		val key = resource as? ItemKey ?: return false

		val listed = filterList(block)
		if (listed.isEmpty()) return true // unconfigured

		return if (blacklisting(block)) key !in listed else key in listed

	}

	override fun describe(block: Block): String {

		val facing = (block.blockData as? Hopper)?.facing ?: return "(not a placed hopper)"

		val listed = filterList(block)
		val mode = if (blacklisting(block)) "BLACKLIST (powered)" else "WHITELIST"

		if (listed.isEmpty()) return "FILTER out $facing, $mode, empty so everything passes"

		return "FILTER out $facing, $mode ${listed.joinToString(",") { it.namespacedKey.key }}"

	}

	/** every distinct variant the hopper holds, which is what it filters on */
	private fun filterList(block: Block): List<ItemKey> =
		(block.state as? Container)?.inventory?.itemKeys().orEmpty()

	/** a powered hopper is disabled, and that is the signal the mode is read from */
	private fun blacklisting(block: Block): Boolean = (block.blockData as? Hopper)?.isEnabled == false

}

/////////////////
///// LOOKUP
/////////////////

/** Every transport component that is not an AnionBlock, and the one lookup everything resolves through. */
object AnionTransportComponents {

	// vanilla blocks cannot implement the interface, so they are adapted here instead
	private val byMaterial: Map<Material, AnionTransportComponent> = mapOf(
		Material.CRAFTING_TABLE to VanillaContainerImporter,
		Material.HOPPER to HopperFilter,
	)

	/** The component [block] is, or null when it is not one. */
	fun at(block: Block): AnionTransportComponent? = of(block.blockData)

	/** The component [data] encodes, or null when it is not one. */
	// off the blockdata rather than a world block, so a ship can classify the states it has stored
	// without reading — and therefore loading — the chunks they sit in
	fun of(data: BlockData): AnionTransportComponent? {

		val noteBlock = data as? NoteBlock
			?: return byMaterial[data.material]

		return AnionBlocks.fromState(noteBlock.instrument, noteBlock.note.id.toInt()) as? AnionTransportComponent

	}

}
