package dev.diena.anion.features.custom.items

import dev.diena.anion.extensions.vec3i
import dev.diena.anion.features.machine.component.MachinePort
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemType

/**
 * Cycles which buffer a port feeds, or empties that buffer onto the ground while sneaking.
 *
 * Held, it reports what the crosshair is on once a second: the port there and what it is currently
 * bound to.
 */
class AnionScrewdriverItem : AnionItem(

	displayName = "Screwdriver",
	itemRepresentation = ItemType.COPPER_INGOT,
	styledDisplayName = Component.text("Screwdriver").color(NamedTextColor.GOLD),

) {

	companion object {

		/** how far the readout looks, in blocks */
		private const val REACH = 8

	}

	override fun onPlayerInteract(event: PlayerInteractEvent) {

		if (event.hand != EquipmentSlot.HAND) return
		if (event.action != Action.RIGHT_CLICK_BLOCK) return

		val block = event.clickedBlock ?: return

		// a screwdriver never places anything and never opens anything
		event.setUseInteractedBlock(Event.Result.DENY)
		event.setUseItemInHand(Event.Result.DENY)

		val player = event.player
		val level = (block.world as CraftWorld).handle

		val port = MachinePort.at(level, block.vec3i) ?: run {
			player.sendActionBar(Component.text("Not a machine port.").color(NamedTextColor.RED))
			return
		}

		if (!player.isSneaking) {

			if (port.machine.buffers.isEmpty()) {
				player.sendActionBar(Component.text("This machine has no buffers.").color(NamedTextColor.RED))
				return
			}

			val bound = port.cycle()

			player.sendActionBar(
				Component.text("${port.kind} -> ${bound ?: "unbound"}").color(NamedTextColor.AQUA)
			)

			return

		}

		val buffer = port.buffer() ?: run {
			player.sendActionBar(Component.text("${port.kind} port is not bound to a buffer.").color(NamedTextColor.RED))
			return
		}

		if (!buffer.spillable) {
			player.sendActionBar(
				Component.text("${buffer.key} will not be dumped — break the machine to empty it.")
					.color(NamedTextColor.RED)
			)
			return
		}

		val held = buffer.used()

		// out through the face that was clicked, so it lands in front of the port rather than inside
		// the machine it came out of
		val front = block.getRelative(event.blockFace).location.toCenterLocation()
		port.machine.spill(buffer, front)

		player.sendActionBar(
			Component.text("Emptied ${buffer.key}, dropped $held").color(NamedTextColor.YELLOW)
		)

	}

	override fun slowTick(player: Player) {

		val target = player.getTargetBlockExact(REACH) ?: return

		val level = (player.world as CraftWorld).handle

		val port = MachinePort.at(level, target.vec3i) ?: run {
			player.sendActionBar(Component.text("no port").color(NamedTextColor.DARK_GRAY))
			return
		}

		val buffer = port.buffer()

		val message = when {
			port.bufferKey == null -> "${port.kind} -> unbound"
			buffer == null -> "${port.kind} -> ${port.bufferKey} (machine broken)"
			else -> "${port.kind} -> ${buffer.key} ${buffer.used()}/${buffer.capacity()}"
		}

		player.sendActionBar(Component.text(message).color(NamedTextColor.AQUA))

	}

}
