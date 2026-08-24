package dev.diena.anion.features.listeners

import dev.astralchroma.processor.annotations.Register
import dev.diena.anion.extensions.vec3i
import dev.diena.anion.features.machine.Machine
import dev.diena.anion.features.machine.component.MachineSign
import dev.diena.anion.features.machine.machine_types.scripting.ControlSeatMachine
import io.papermc.paper.event.player.PlayerOpenSignEvent
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerAnimationEvent
import org.bukkit.event.player.PlayerAnimationType
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.EquipmentSlot

/**
 * Where a player's clicks and keys meet the datachannel system: machine signs, and the control seat.
 *
 * Both are click-driven rather than block-driven, so neither can live on an AnionBlock handler — a
 * machine sign is a vanilla sign and a seat is a vanilla stair.
 */
@Register
object AnionScriptingListeners : Listener {

	/** A sign in a machine is that machine's readout. Nobody types into one. */
	// signs are waxed on assembly too; this catches one that had its wax taken off with an axe
	@EventHandler
	fun onSignOpen(event: PlayerOpenSignEvent) {

		val block = event.sign.block
		val level = (block.world as CraftWorld).handle

		if (MachineSign.ownerAt(level, block.vec3i) == null) return

		event.isCancelled = true

	}

	@EventHandler
	fun onInteract(event: PlayerInteractEvent) {

		if (event.hand != EquipmentSlot.HAND) return

		val player = event.player

		// a seated pilot's clicks are inputs, wherever they are pointing
		ControlSeatMachine.seatOf(player)?.let { seat ->

			when (event.action) {

				Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK -> seat.registerClick(right = true)
				Action.LEFT_CLICK_AIR, Action.LEFT_CLICK_BLOCK -> seat.registerClick(right = false)

				else -> {}

			}

			// clicking the seat you are in is how you get out of it
			val clicked = event.clickedBlock
			if (clicked != null && event.action == Action.RIGHT_CLICK_BLOCK && seatAt(clicked) === seat) {

				seat.toggleSeat(player)
				event.isCancelled = true

			}

			return

		}

		if (event.action != Action.RIGHT_CLICK_BLOCK) return

		val block = event.clickedBlock ?: return
		val level = (block.world as CraftWorld).handle

		seatAt(block)?.let { seat ->

			seat.toggleSeat(player)
			event.isCancelled = true

			return

		}

		val (machine, offset) = MachineSign.ownerAt(level, block.vec3i) ?: return

		machine.onSignClick(offset, player, front = true)
		event.isCancelled = true

	}

	/** Holding left click repeats the swing, which is the only thing that makes `lc` a holdable input. */
	@EventHandler
	fun onSwing(event: PlayerAnimationEvent) {

		if (event.animationType != PlayerAnimationType.ARM_SWING) return

		ControlSeatMachine.seatOf(event.player)?.registerClick(right = false)

	}

	@EventHandler
	fun onQuit(event: PlayerQuitEvent) {

		ControlSeatMachine.seatOf(event.player)?.vacate()

	}

	/** A pilot's speeds are saved with them, so a server that went down mid-flight left one frozen. */
	@EventHandler
	fun onJoin(event: PlayerJoinEvent) {

		ControlSeatMachine.repairFrozen(event.player)

	}

	/**
	 * A pilot may look wherever they like, but they do not walk out of their seat.
	 *
	 * Refusing the move rather than teleporting them back: a teleport every tick fights the client's own
	 * prediction, and fights the carrier, which teleports everything in its hitbox when the ship moves.
	 * Two things writing a player's position in one tick is what makes a seat jitter.
	 */
	@EventHandler
	fun onMove(event: PlayerMoveEvent) {

		// a ship carries its passengers by teleporting them, and that has to get through
		if (event is PlayerTeleportEvent) return

		val from = event.from
		val to = event.to

		// look-only movement is most of these events, and costs nothing to allow
		if (from.x == to.x && from.y == to.y && from.z == to.z) return

		if (ControlSeatMachine.seatOf(event.player) == null) return

		event.setTo(Location(from.world, from.x, from.y, from.z, to.yaw, to.pitch))

	}

	/** The seat whose structure holds [block], or null. */
	private fun seatAt(block: Block): ControlSeatMachine? {

		val level = (block.world as CraftWorld).handle

		return Machine.machinesAt(level, block.vec3i).filterIsInstance<ControlSeatMachine>().firstOrNull()

	}

}
