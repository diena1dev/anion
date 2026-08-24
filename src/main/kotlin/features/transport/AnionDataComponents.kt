package dev.diena.anion.features.transport

import dev.diena.anion.extensions.CARTESIAN_FACES
import dev.diena.anion.extensions.axis
import dev.diena.anion.extensions.faces
import dev.diena.anion.features.custom.AnionResource
import dev.diena.anion.features.custom.blocks.AnionBlock
import net.kyori.adventure.text.Component
import org.bukkit.Instrument
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Orientable

/**
 * A transport component data may travel through. Item components are not, and data never crosses one —
 * a pipe run into a dataport carries nothing.
 *
 * Data is not a resource that gets moved a pass at a time, so nothing here drives. These components are
 * geometry: [DataNetwork] walks them to work out which machines a mainframe is wired to.
 */
interface AnionDataComponent : AnionTransportComponent {

	/** the item pass never carries data, so a data line is a dead end to it */
	override fun accepts(block: Block, resource: AnionResource): Boolean = false

	/** nothing drives a data line, so recording where they are would only slow the item pass down */
	override val indexed: Boolean get() = false

}

/**
 * The data line, bound to the vanilla chain. Carries along the axis it was hung on, in at one end and
 * out at the other, either way round — turning a corner is what the junction is for.
 */
object DataLine : AnionDataComponent {

	override fun exitsFor(block: Block, entryFace: BlockFace): List<BlockFace>? {

		val axis = (block.blockData as? Orientable)?.axis ?: return null
		if (entryFace.axis != axis) return null // entered through a side rather than an end

		return listOf(entryFace.oppositeFace)

	}

	override fun describe(block: Block): String {

		val axis = (block.blockData as? Orientable)?.axis ?: return "(no axis: not a placed chain)"

		return "DATA LINE axis=$axis (carries signal between ${axis.faces.joinToString(" and ")})"

	}

}

/** Takes a data line in on any face and continues it out of every other one. */
class AnionDataJunctionBlock(

	displayName: String,
	instrument: Instrument,
	note: Int,
	styledDisplayName: Component = Component.text(displayName),

) : AnionBlock(displayName, instrument, note, styledDisplayName = styledDisplayName), AnionDataComponent {

	override fun exitsFor(block: Block, entryFace: BlockFace): List<BlockFace> =
		CARTESIAN_FACES.filter { it != entryFace }

	override fun describe(block: Block): String = "DATA JUNCTION (any side in, any other out)"

}
