package dev.diena.anion.features.transport

import dev.diena.anion.extensions.CARTESIAN_FACES
import dev.diena.anion.extensions.anionAxis
import dev.diena.anion.extensions.anionBlock
import dev.diena.anion.extensions.axis
import dev.diena.anion.extensions.faces
import dev.diena.anion.extensions.itemKeys
import dev.diena.anion.extensions.plus
import dev.diena.anion.extensions.vec3i
import dev.diena.anion.features.custom.ItemKey
import dev.diena.anion.features.custom.blocks.AnionBlock
import dev.diena.anion.features.custom.blocks.AnionPillarBlock
import net.kyori.adventure.text.Component
import org.bukkit.Axis
import org.bukkit.Instrument
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace

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

	override fun describe(block: Block): String {

		val axis = block.anionAxis ?: return "(no axis: not a placed pipe)"

		return "axis=$axis (takes items in through ${axis.faces.joinToString(" or ")})"

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

/////////////////
///// LOOKUP
/////////////////

/** Every transport component that is not an AnionBlock, and the one lookup everything resolves through. */
object AnionTransportComponents {

	// vanilla blocks cannot implement the interface, so they are adapted here instead
	private val byMaterial: Map<Material, AnionTransportComponent> = mapOf(
		Material.CRAFTING_TABLE to VanillaContainerImporter,
	)

	/** The component [block] is, or null when it is not one. */
	fun at(block: Block): AnionTransportComponent? =
		block.anionBlock as? AnionTransportComponent ?: byMaterial[block.type]

}
