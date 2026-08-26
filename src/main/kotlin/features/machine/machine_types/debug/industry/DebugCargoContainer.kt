package dev.diena.anion.features.machine.machine_types.debug.industry

import dev.diena.anion.features.custom.blocks.AnionBlocks
import dev.diena.anion.features.machine.BlockSet
import dev.diena.anion.features.machine.component.BulkItemBuffer
import dev.diena.anion.features.machine.component.MachinePort
import dev.diena.anion.features.machine.machine_types.PortedMachine
import org.bukkit.block.BlockType

val DEBUG_CARGO_CONTAINER_STRUCTURE =
	BlockSet.new("medium_cargo_container")
		.core('C', AnionBlocks.COPPER_MACHINE_CASING)

		.assign('I', AnionBlocks.COPPER_MACHINE_CASING)
		.assign('I', AnionBlocks.COPPER_MACHINE_BUS)
		.assign('I', AnionBlocks.COPPER_MACHINE_DISPLAY)

		.assign('c', BlockType.WAXED_COPPER_BLOCK)

		// floor. the origin is this slice's first cell, so local offsets run 0..5 x, 0..2 y, 0..2 z
		.slice(
			"III",
			"ccc",
			"III",
			"III",
			"ccc",
			"ICI",
		)
		// walls. the blanks are the cargo void — unchecked, so anything may sit in there
		.slice(
			"III",
			"c c",
			"I I",
			"I I",
			"c c",
			"III",
		)
		// roof
		.slice(
			"III",
			"ccc",
			"III",
			"III",
			"ccc",
			"III",
		)

		.build()

// TODO: disassembly spills the whole buffer as dropped items, which for a full medium container is
//       24000 items — roughly 375 stacks in one tick. Needs either a drop budget spread over several
//       ticks or a packaged-container item that carries the contents with it.
/**
 * Bulk item storage. Holds [typeLimit] distinct item variants totalling [totalCapacity] items.
 */
class DebugCargoContainer(

	displayName: String,
	blockSet: BlockSet,
	val typeLimit: Int,
	val totalCapacity: Long,

) : PortedMachine(displayName, blockSet) {

	companion object {

		/** the one buffer a container has */
		const val CARGO_BUFFER = "cargo"

	}

	/** null until the machine has been assembled. */
	val cargo: BulkItemBuffer? get() = buffers[CARGO_BUFFER] as? BulkItemBuffer

	override fun onAssemble() {

		// declared before super, which resolves the ports and replays their saved bindings — those
		// bindings resolve against this map, so it has to be populated first
		buffers[CARGO_BUFFER] = BulkItemBuffer(CARGO_BUFFER, typeLimit, capacity = totalCapacity)

		super.onAssemble()

		// a container has exactly one buffer, so an item port that has never been cycled feeds it
		for (port in ports.values) {
			if (port.kind == MachinePort.Kind.BUS && port.bufferKey == null) port.bind(CARGO_BUFFER)
		}

	}

	override fun tick() {
		// NO-OP. a container stores, it does not run. transport pushes and pulls through its ports.
	}

	override fun slowTick() {
		// NO-OP atm
	}

}
