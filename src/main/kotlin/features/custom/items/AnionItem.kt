package dev.diena.anion.features.custom.items

import dev.astralchroma.processor.annotations.Register
import dev.diena.anion.Anion
import dev.diena.anion.Tasks
import dev.diena.anion.extensions.set
import dev.diena.anion.extensions.toAnionItem
import dev.diena.anion.features.custom.AnionResource
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent
import net.kyori.adventure.text.Component
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.ItemType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 *
 * AnionItems
 *
 * DO NOT USE SPECIAL CHARACTERS IN ITEM DISPLAY NAMES! (please god no regex)
 *
 * @param displayName        Required: Display name of the item.
 * @param itemRepresentation Required: ItemType Representation of the CustomItem. Use Block ItemTypes when registering AnionBlocks.
 * @param stacksTo           Optional: Change the amount the ItemStack can stack to. Cannot exceed 64.
 * @param styledDisplayName  Optional: Uses a [Component] to style the name of the item. Allows setting font, color, etc.
 * @param namespacedKey      Optional: Internal override namespacedKey of the item. Gets serialized to the ITEM_MODEL DataComponentType.
 *
 * */
@Suppress("UnstableApiUsage")
open class AnionItem(

	displayName: String,
	private val itemRepresentation: ItemType,
	stacksTo: Int = 64,
	styledDisplayName: Component = Component.text(displayName),
	override val namespacedKey: NamespacedKey = NamespacedKey(Anion.NAMESPACE, displayName.replace(" ", "_").lowercase()),

	private val interactHandler: ((PlayerInteractEvent) -> Unit)? = null,
	private val swapHandler: ((PlayerSwapHandItemsEvent) -> Unit)? = null,
	private val addHandler: ((Player) -> Unit)? = null,
	private val removeHandler: ((Player) -> Unit)? = null,

) : AnionResource {

	// go-go gadget internal item stack
	// override with caution
	protected open val internalItemStack: ItemStack = this.itemRepresentation.createItemStack()

	// item stack magic
	init {

		// it can be null, so stop complaining
		if (internalItemStack != null) {
			internalItemStack[DataComponentTypes.ITEM_NAME] = styledDisplayName
			internalItemStack[DataComponentTypes.ITEM_MODEL] = namespacedKey
			internalItemStack[DataComponentTypes.MAX_STACK_SIZE] = stacksTo
		}

	}

	fun asItemStack(quantity: Int = 1): ItemStack {
		return internalItemStack.asQuantity(quantity)
	}

	open fun onPlayerInteract(event: PlayerInteractEvent) { interactHandler?.invoke(event) }
	open fun onPlayerSwapHand(event: PlayerSwapHandItemsEvent) { swapHandler?.invoke(event) }
	open fun onEntityShootBow(event: EntityShootBowEvent) {}

	/**
	 * Whether this item does its own thing with a right-clicked block, so the block should leave that
	 * interaction alone.
	 *
	 * A tool clicked on a machine port is being used on it, not fed into it.
	 */
	open val handlesBlockInteraction: Boolean get() = false

	/**
	 * Called once a second for every player holding this item in their main hand, main thread.
	 *
	 * For readouts a tool wants to keep current without an interaction — what the crosshair is on,
	 * what state the thing under it is in.
	 */
	open fun slowTick(player: Player) {}

	/** [player] no longer holds this item in their main hand. */
	open fun onRemove(player: Player) { removeHandler?.invoke(player) }

	/** [player] now holds this item in their main hand. */
	open fun onAdd(player: Player) { addHandler?.invoke(player) }

}

@Register
object AnionItemDispatcher : Listener {

	/** the anion item each player currently holds in their main hand. */
	private val equippedItems = ConcurrentHashMap<UUID, AnionItem>()

	/** Drives [AnionItem.slowTick] for whoever is holding one. Scheduled from Anion.onEnable. */
	fun slowTickEquipped() {

		for ((uuid, item) in equippedItems) {

			val player = Anion.instance.getPlayer(uuid) ?: continue

			item.slowTick(player)

		}

	}

	@EventHandler
	fun onPlayerInteract(event: PlayerInteractEvent) {
		event.item?.toAnionItem()?.onPlayerInteract(event)
	}

	@EventHandler
	fun onPlayerSwapHand(event: PlayerSwapHandItemsEvent) {
		refreshEquipped(event.player)

		val item = event.mainHandItem.toAnionItem()
			?: event.offHandItem.toAnionItem()
			?: return
		item.onPlayerSwapHand(event)
	}

	@EventHandler
	fun onEntityShootBow(event: EntityShootBowEvent) {
		event.bow?.toAnionItem()?.onEntityShootBow(event)
	}

	@EventHandler
	fun onPlayerItemHeld(event: PlayerItemHeldEvent) { refreshEquipped(event.player) }

	@EventHandler
	fun onPlayerInventorySlotChange(event: PlayerInventorySlotChangeEvent) { refreshEquipped(event.player) }

	@EventHandler
	fun onPlayerDropItem(event: PlayerDropItemEvent) { refreshEquipped(event.player) }

	@EventHandler
	fun onPlayerJoin(event: PlayerJoinEvent) { refreshEquipped(event.player) }

	// MONITOR so keepInventory is read after every other plugin has had its say on it
	@EventHandler(priority = EventPriority.MONITOR)
	fun onPlayerDeath(event: PlayerDeathEvent) {

		if (event.keepInventory) return // the item never left their hand
		unequip(event.entity)

	}

	@EventHandler
	fun onPlayerRespawn(event: PlayerRespawnEvent) { refreshEquipped(event.player) }

	@EventHandler
	fun onPlayerQuit(event: PlayerQuitEvent) { unequip(event.player) }

	/** re-reads [player]'s main hand next tick and fires the onRemove/onAdd pair if the item there changed.
	 *  deferred because the events that trigger it fire before the inventory actually changes. */
	private fun refreshEquipped(player: Player) {

		Tasks.runSync {

			if (!player.isOnline) return@runSync

			val equipped = player.inventory.itemInMainHand.toAnionItem()
			val previous =
				if (equipped == null) equippedItems.remove(player.uniqueId)
				else equippedItems.put(player.uniqueId, equipped)

			if (previous === equipped) return@runSync

			previous?.onRemove(player)
			equipped?.onAdd(player)

		}

	}

	/** drops [player]'s tracked item and fires its onRemove. */
	private fun unequip(player: Player) {

		equippedItems.remove(player.uniqueId)?.onRemove(player)

	}

}
