package dev.diena.anion.features.machine.component

import dev.diena.anion.extensions.toAnionItem
import dev.diena.anion.extensions.vec3i
import dev.diena.anion.features.custom.ItemKey
import dev.diena.anion.features.custom.blocks.AnionBlock
import dev.diena.anion.features.custom.blocks.AnionBlocks
import dev.diena.anion.features.machine.BlockMatcher
import dev.diena.anion.features.machine.Machine
import dev.diena.anion.features.machine.machine_types.PortedMachine
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minecraft.core.Vec3i
import net.minecraft.server.level.ServerLevel
import org.bukkit.GameMode
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack

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

	/**
	 * Moves this port to the machine's next buffer, then round to unbound. Returns the key it landed
	 * on, or null for unbound.
	 *
	 * A machine with one buffer can bind every port to it at assembly and never think about this. A
	 * machine with three cannot, so which port feeds which is the player's to say.
	 */
	// TODO: a bus has no business bridging a gas buffer. gating the cycle by port kind needs the
	//       resource family each kind maps to to be settled first, and it is not. until then the
	//       buffer's own resourceType gate makes a wrong binding inert rather than harmful.
	fun cycle(): String? {

		val keys = machine.buffers.keys.toList()
		if (keys.isEmpty()) return null

		val index = keys.indexOf(bufferKey)
		val next = when {
			bufferKey == null -> keys.first() // unbound, so start at the top
			index < 0 -> keys.first()          // bound to something that no longer exists
			index == keys.lastIndex -> null    // round back off the end
			else -> keys[index + 1]
		}

		bind(next)
		machine.dirty = true

		return next

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
		 * Puts what the player is holding into the buffer behind the port they clicked.
		 *
		 * The hand-loading path, so a machine can be fed without building a transport network to it.
		 * What actually fits is decided by the buffer, not here: the wrong resource for it, or no room,
		 * takes nothing and says so.
		 */
		fun insertHeld(event: PlayerInteractEvent) {

			if (event.action != Action.RIGHT_CLICK_BLOCK) return
			if (event.hand != EquipmentSlot.HAND) return

			val player = event.player
			val held = player.inventory.itemInMainHand
			if (held.isEmpty) return

			// a tool clicked on a port is being used on it, not fed into it
			if (held.toAnionItem()?.handlesBlockInteraction == true) return

			val block = event.clickedBlock ?: return
			val port = at((block.world as CraftWorld).handle, block.vec3i) ?: return

			event.setUseInteractedBlock(Event.Result.DENY)
			event.setUseItemInHand(Event.Result.DENY)

			val buffer = port.buffer() ?: run {
				player.sendActionBar(Component.text("${port.kind} port is not bound to a buffer.").color(NamedTextColor.RED))
				return
			}

			val key = ItemKey.of(held)

			if (!buffer.accepts(key)) {
				player.sendActionBar(Component.text("${buffer.key} will not take that.").color(NamedTextColor.RED))
				return
			}

			val inserted = buffer.insert(key, held.amount.toLong()).toInt()

			if (inserted <= 0) {
				player.sendActionBar(Component.text("${buffer.key} is full.").color(NamedTextColor.RED))
				return
			}

			// the click has been spent. without this a held block item would also get placed against
			// the port by the listener's own placement path, on top of going into the buffer.
			event.isCancelled = true

			// creative hands are bottomless everywhere else, so they are here too
			if (player.gameMode != GameMode.CREATIVE) {
				val remaining = held.amount - inserted
				player.inventory.setItemInMainHand(if (remaining > 0) held.asQuantity(remaining) else ItemStack.empty())
			}

			player.sendActionBar(
				Component.text("+$inserted -> ${buffer.key} ${buffer.used()}/${buffer.capacity()}")
					.color(NamedTextColor.GREEN)
			)

		}

		/** The port occupying [cell], on whichever machine owns it, or null if that cell is not one. */
		fun at(level: ServerLevel, cell: Vec3i): MachinePort? {

			for (machine in Machine.machinesAt(level, cell).filterIsInstance<PortedMachine>()) {

				machine.ports.values
					.firstOrNull { machine.localToWorld(it.offset) == cell }
					?.let { return it }

			}

			return null

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
