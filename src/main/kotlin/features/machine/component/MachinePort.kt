package dev.diena.anion.features.machine.component

import dev.diena.anion.features.custom.blocks.AnionBlock
import dev.diena.anion.features.custom.blocks.AnionBlocks
import dev.diena.anion.features.machine.BlockMatcher
import dev.diena.anion.features.machine.Machine
import net.minecraft.core.Vec3i

/**
 * An access point punched into a machine's casing. A port only exposes the buffer behind it — it
 * takes no part in any transport loop, which attaches its own adapter blocks on the outside.
 *
 * Ports are resolved once, at assembly, from the variants that actually filled the casing cells.
 * Swapping a casing block for a port block afterwards breaks the structure instead of adding a port.
 */
class MachinePort(

	val machine: Machine,
	/** cell this port occupies, relative to the machine origin */
	val offset: Vec3i,
	val kind: Kind,

) {

	/** buffer this port is bound to. cycled by the player, null until bound. */
	var bufferKey: String? = null; internal set

	/** The buffer behind this port, or null while the machine is broken. */
	fun buffer(): MachineBuffer? {

		if (!machine.intact) return null // a broken machine exposes nothing

		return machine.buffers[bufferKey ?: return null]

	}

	/** Binds this port to [bufferKey], releasing whatever it was bound to. */
	fun bind(bufferKey: String?) {

		this.bufferKey?.let { machine.buffers[it]?.boundPorts?.remove(this) }

		this.bufferKey = bufferKey
		bufferKey?.let { machine.buffers[it]?.boundPorts?.add(this) }

	}

	/** What a port is for. Readouts and data links are ports too — they just expose a different thing. */
	enum class Kind { BUS, VALVE, CONDUIT, DATA, DISPLAY }

	companion object {

		private val kindsByBlock: Map<AnionBlock, Kind> = mapOf(
			AnionBlocks.COPPER_MACHINE_BUS to Kind.BUS,
			AnionBlocks.COPPER_MACHINE_VALVE to Kind.VALVE,
			AnionBlocks.COPPER_MACHINE_CONDUIT to Kind.CONDUIT,
			AnionBlocks.COPPER_MACHINE_DATAPORT to Kind.DATA,
			AnionBlocks.COPPER_MACHINE_DISPLAY to Kind.DISPLAY,
		)

		/** The port kind [matcher] describes, or null when it is plain casing or a vanilla block. */
		fun kindOf(matcher: BlockMatcher): Kind? =
			(matcher as? BlockMatcher.Custom)?.let { kindsByBlock[it.block] }

	}

}
