package dev.diena.anion.features.custom.items

import dev.diena.anion.Anion
import dev.diena.anion.extensions.set
import dev.diena.anion.extensions.toAnionItem
import io.papermc.paper.datacomponent.DataComponentTypes
import net.kyori.adventure.text.Component
import org.bukkit.NamespacedKey
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.ItemType

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
	val namespacedKey: NamespacedKey = NamespacedKey(Anion.NAMESPACE, displayName.replace(" ", "_").lowercase()),

	private val interactHandler: ((PlayerInteractEvent) -> Unit)? = null,
	private val swapHandler: ((PlayerSwapHandItemsEvent) -> Unit)? = null,
) {

	// go-go gadget internal item stack
	// override with caution
	protected open val internalItemStack: ItemStack = this.itemRepresentation.createItemStack()

	// item stack magic
	init {
		internalItemStack[DataComponentTypes.ITEM_NAME] = styledDisplayName
		internalItemStack[DataComponentTypes.ITEM_MODEL] = namespacedKey
		internalItemStack[DataComponentTypes.MAX_STACK_SIZE] = stacksTo
	}

	fun asItemStack(quantity: Int = 1): ItemStack {
		return internalItemStack.asQuantity(quantity)
	}

	open fun onPlayerInteract(event: PlayerInteractEvent) { interactHandler?.invoke(event) }
	open fun onPlayerSwapHand(event: PlayerSwapHandItemsEvent) { swapHandler?.invoke(event) }
	open fun onEntityShootBow(event: EntityShootBowEvent) {}
	open fun onRemove() {}
	open fun onAdd() {}

}

class AnionItemDispatcher : Listener {

	@EventHandler
	fun onPlayerInteract(event: PlayerInteractEvent) {
		event.item?.toAnionItem()?.onPlayerInteract(event)
	}

	@EventHandler
	fun onPlayerSwapHand(event: PlayerSwapHandItemsEvent) {
		val item = event.mainHandItem.toAnionItem()
			?: event.offHandItem.toAnionItem()
			?: return
		item.onPlayerSwapHand(event)
	}

	@EventHandler
	fun onEntityShootBow(event: EntityShootBowEvent) {
		event.bow?.toAnionItem()?.onEntityShootBow(event)
	}

}
