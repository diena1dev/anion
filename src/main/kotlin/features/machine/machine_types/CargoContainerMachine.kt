package dev.diena.anion.features.machine.machine_types

import dev.diena.anion.features.custom.blocks.AnionBlocks
import dev.diena.anion.features.machine.BlockSet
import dev.diena.anion.features.machine.component.BulkItemBuffer
import dev.diena.anion.features.machine.component.MachinePort

/**
 * Six long, three wide, three tall. Floor and roof are solid, the middle course is walls only, so the
 * shell is 50 blocks around a 4x1x1 void.
 *
 * Rows run along x, characters along z, and each slice() is one course up.
 */
val MEDIUM_CARGO_CONTAINER_STRUCTURE =
	BlockSet.new("medium_cargo_container")
		// no dedicated core block: a crate is casing all the way round, so the first casing cell in
		// the floor anchors it. the casing is listed under assign() below too — core() de-duplicates.
		.core('I', AnionBlocks.COPPER_MACHINE_CASING)

		// any casing cell may be swapped for a port at build time. no valve — nothing here is a fluid
		.assign('I', AnionBlocks.COPPER_MACHINE_CASING)
		.assign('I', AnionBlocks.COPPER_MACHINE_BUS)
		.assign('I', AnionBlocks.COPPER_MACHINE_CONDUIT)
		.assign('I', AnionBlocks.COPPER_MACHINE_DISPLAY)

		// floor. the origin is this slice's first cell, so local offsets run 0..5 x, 0..2 y, 0..2 z
		.slice(
			"III",
			"III",
			"III",
			"III",
			"III",
			"III",
		)
		// walls. the blanks are the cargo void — unchecked, so anything may sit in there
		.slice(
			"III",
			"I I",
			"I I",
			"I I",
			"I I",
			"III",
		)
		// roof
		.slice(
			"III",
			"III",
			"III",
			"III",
			"III",
			"III",
		)

		.build()

/**
 * Bulk item storage. Holds [typeLimit] distinct item variants totalling [totalCapacity] items, and
 * nothing else — no recipes, no processing.
 *
 * There is deliberately no way to browse the contents from the container itself; that is a storage
 * terminal's job. Items get in and out through its bus ports.
 *
 * TODO: disassembly spills the whole buffer as dropped items, which for a full medium container is
 *       24000 items — roughly 375 stacks in one tick. Needs either a drop budget spread over several
 *       ticks or a packaged-container item that carries the contents with it.
 */
class CargoContainerMachine(

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
		buffers[CARGO_BUFFER] = BulkItemBuffer(CARGO_BUFFER, typeLimit, totalCapacity)

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
