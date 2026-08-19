package dev.diena.anion.features.machine.machine_types

import dev.diena.anion.features.machine.BlockSet
import dev.diena.anion.features.machine.Machine
import dev.diena.anion.features.machine.component.MachinePort
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag

/**
 * A machine whose casing accepts port blocks. Ports are resolved once, at assembly, from the variants
 * that actually filled the structure — swapping a casing block for a port block afterwards breaks the
 * structure instead of adding a port.
 */
abstract class PortedMachine(

	displayName: String,
	blockSet: BlockSet?,

) : Machine(displayName, blockSet) {

	/** ports on this machine, keyed by the cell they occupy. frozen until reassembly. */
	val ports: MutableMap<Vec3i, MachinePort> = mutableMapOf()

	/** buffer bindings read back from disk, applied once ports exist */
	private var restoredBindings: Map<Vec3i, String> = emptyMap()

	override fun onAssemble() {

		super.onAssemble()

		ports.clear()

		// resolvedStructure already says which variant filled every cell, so port discovery is a
		// filter over it rather than a second pass over the world
		for ((offset, matcher) in resolvedStructure) {

			val kind = MachinePort.kindOf(matcher) ?: continue
			ports[offset] = MachinePort(this, offset, kind)

		}

		for ((offset, bufferKey) in restoredBindings) ports[offset]?.bind(bufferKey)
		restoredBindings = emptyMap()

	}

	/** Which buffer each port feeds. Player-cycled, so it cannot be re-derived from the structure. */
	override fun saveState(tag: CompoundTag) {

		super.saveState(tag)

		val bindings = ListTag()

		for (port in ports.values) {

			val bufferKey = port.bufferKey ?: continue

			val entry = CompoundTag()
			entry.putLong("cell", BlockPos.asLong(port.offset.x, port.offset.y, port.offset.z))
			entry.putString("buffer", bufferKey)

			bindings.add(entry)

		}

		tag.put("portBindings", bindings)

	}

	// runs before onAssemble(), so the bindings are stashed until there are ports to apply them to
	override fun loadState(tag: CompoundTag) {

		super.loadState(tag)

		val bindings = mutableMapOf<Vec3i, String>()

		for (entry in tag.getListOrEmpty("portBindings").filterIsInstance<CompoundTag>()) {

			val cell = BlockPos.of(entry.getLongOr("cell", 0L))
			bindings[Vec3i(cell.x, cell.y, cell.z)] = entry.getStringOr("buffer", "")

		}

		restoredBindings = bindings

	}

}
