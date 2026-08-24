package dev.diena.anion.features.custom.items

import dev.diena.anion.extensions.vec3i
import dev.diena.anion.features.machine.Machine
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
 * Assembles the machine the clicked block would complete, dumps a full readout when there is already
 * one there, and tears one down while sneaking.
 *
 * Held, it reports what the crosshair is on once a second: the machine there, or the one that block
 * would complete if it were assembled.
 */
class AnionWrenchItem : AnionItem(

	displayName = "Wrench",
	itemRepresentation = ItemType.STICK,
	stacksTo = 1,

) {

	companion object {

		/** how far the readout looks, in blocks */
		private const val REACH = 8

	}

	override val handlesBlockInteraction: Boolean get() = true

	override fun onPlayerInteract(event: PlayerInteractEvent) {

		if (event.hand != EquipmentSlot.HAND) return
		if (event.action != Action.RIGHT_CLICK_BLOCK) return

		val block = event.clickedBlock ?: return

		// a wrench never places anything and never opens anything
		event.setUseInteractedBlock(Event.Result.DENY)
		event.setUseItemInHand(Event.Result.DENY)

		val player = event.player
		val level = (block.world as CraftWorld).handle
		val cell = block.vec3i

		if (player.isSneaking) {

			val machines = Machine.machinesAt(level, cell)

			if (machines.isEmpty()) {
				player.sendActionBar(Component.text("no machine here").color(NamedTextColor.RED))
				return
			}

			// machines may share structure cells, so one block can belong to more than one
			for (machine in machines) machine.disassemble()

			player.sendActionBar(
				Component.text("disassembled ${machines.joinToString(", ") { it.namespacedKey.key }}")
					.color(NamedTextColor.YELLOW)
			)

			return

		}

		// already a machine here, so there is nothing to assemble and the interesting thing is its state
		val existing = Machine.machinesAt(level, cell)

		if (existing.isNotEmpty()) {

			for (machine in existing) {
				for (line in machine.debugReport()) player.sendMessage(Component.text(line).color(NamedTextColor.GRAY))
			}

			return

		}

		val candidates = Machine.candidatesAt(level, cell)

		if (candidates.isEmpty()) {
			player.sendActionBar(Component.text("no machine candidate").color(NamedTextColor.RED))
			return
		}

		// a block that completes two different structures at once is an ambiguity, not a coin flip
		if (candidates.size > 1) {
			player.sendActionBar(
				Component.text("ambiguous: ${candidates.size} structures match.")
					.color(NamedTextColor.RED)
			)
			return
		}

		val candidate = candidates.first()
		val assembled = candidate.factory().assemble(level, candidate.origin, candidate.rotation)

		if (assembled == null) {
			player.sendActionBar(Component.text("assembly failed | a port cell is already claimed.").color(NamedTextColor.RED))
			return
		}

		player.sendActionBar(
			Component.text("assembled ${assembled.namespacedKey.key}").color(NamedTextColor.GREEN)
		)

	}

	override fun slowTick(player: Player) {

		val target = player.getTargetBlockExact(REACH) ?: return

		val level = (player.world as CraftWorld).handle
		val cell = target.vec3i

		val machines = Machine.machinesAt(level, cell)

		if (machines.isNotEmpty()) {

			val readout = machines.joinToString(", ") {
				"${it.namespacedKey.key}${if (it.intact) "" else " (broken)"}"
			}

			player.sendActionBar(Component.text(readout).color(NamedTextColor.AQUA))

			return

		}

		// not a machine yet, but the wrench's whole job is telling you whether it could be
		val candidates = Machine.candidatesAt(level, cell)

		val message = when (candidates.size) {
			0 -> Component.text("no machine").color(NamedTextColor.DARK_GRAY)
			1 -> Component.text("can assemble: ${candidates.first().factory().namespacedKey.key}").color(NamedTextColor.GREEN)
			else -> Component.text("ambiguous: ${candidates.size} structures match").color(NamedTextColor.RED)
		}

		player.sendActionBar(message)

	}

}
