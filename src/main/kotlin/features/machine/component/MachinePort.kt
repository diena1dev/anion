package dev.diena.anion.features.machine.component

import dev.diena.anion.extensions.vec3i
import dev.diena.anion.features.custom.blocks.AnionBlock
import dev.diena.anion.features.custom.blocks.AnionBlocks
import dev.diena.anion.features.machine.BlockMatcher
import dev.diena.anion.features.machine.Machine
import dev.diena.anion.features.machine.MachineIndex
import dev.diena.anion.features.machine.machine_types.PortedMachine
import dev.diena.anion.features.starship.Starship
import net.kyori.adventure.text.Component
import net.minecraft.core.Vec3i
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot

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

		/**
		 * Moves the port the player right-clicked on to the machine's next buffer, then round to
		 * unbound. Bare hand only, since a full hand means they were trying to place a block.
		 *
		 * A machine with one buffer can bind every port to it at assembly and never think about this.
		 * A machine with three cannot, so which port feeds which is the player's to say.
		 */
		// TODO: a bus has no business bridging a gas buffer. gating the cycle by port kind needs the
		//       resource family each kind maps to to be settled first, and it is not. until then the
		//       buffer's own resourceType gate makes a wrong binding inert rather than harmful.
		fun cycleBinding(event: PlayerInteractEvent) {

			if (event.action != Action.RIGHT_CLICK_BLOCK) return
			if (event.hand != EquipmentSlot.HAND) return
			if (!event.player.inventory.itemInMainHand.isEmpty) return

			val block = event.clickedBlock ?: return
			val level = (block.world as CraftWorld).handle
			val cell = block.vec3i

			// grounded machines live in the cell index; carried ones are tracked by their ship instead
			val machines = MachineIndex.machinesAt(level, cell) +
				Starship.starshipAt(level, cell)?.machines?.machinesHolding(cell).orEmpty()

			for (machine in machines.distinct().filterIsInstance<PortedMachine>()) {

				val port = machine.ports.values.firstOrNull { machine.localToWorld(it.offset) == cell } ?: continue

				val keys = machine.buffers.keys.toList()
				if (keys.isEmpty()) {
					event.player.sendMessage(Component.text("${machine.namespacedKey.key} has no buffers to bind to."))
					return
				}

				val index = keys.indexOf(port.bufferKey)
				val next = when {
					port.bufferKey == null -> keys.first()   // unbound, so start at the top
					index < 0 -> keys.first()                 // bound to something that no longer exists
					index == keys.lastIndex -> null           // round back off the end
					else -> keys[index + 1]
				}

				port.bind(next)
				machine.dirty = true

				event.player.sendMessage(
					Component.text("${port.kind} port at ${port.offset} -> ${next ?: "unbound"}")
				)

				return

			}

		}

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
