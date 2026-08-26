package dev.diena.anion.features.custom

import dev.diena.anion.extensions.toAnionItem
import org.bukkit.NamespacedKey
import dev.diena.anion.features.custom.items.AnionItem
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.ItemType

interface AnionResource {

	val namespacedKey: NamespacedKey

}

/**
 * Identity of one item variant. Two stacks of the same item with different data components are
 * different keys, so damaged tools, enchanted gear and custom item states each count on their own.
*/
// equality is the ItemStack's own, which compares data components but not amount. normalizing to a
// single item on the way in is what makes it usable as a map key.
class ItemKey private constructor(

	/** always a single item. never hand this out to be mutated. */
	val stack: ItemStack,

) : AnionResource {

	/** the registered AnionItem this is a variant of, or null for a plain vanilla item */
	val anionItem get() = stack.toAnionItem()

	// vanilla items have no Anion registry entry, so they fall back to their material key.
	override val namespacedKey: NamespacedKey
		get() = anionItem?.namespacedKey ?: stack.type.key

	override fun equals(other: Any?) = other is ItemKey && stack == other.stack
	override fun hashCode() = stack.hashCode()
	override fun toString() = "ItemKey(${namespacedKey.key})"

	/** Bytes that [of] can rebuild this key from. Round-trips data components. */
	fun toBytes(): ByteArray = stack.serializeAsBytes()

	/** A stack of [quantity] of this variant. */
	fun asItemStack(quantity: Int = 1): ItemStack = stack.asQuantity(quantity)

	companion object {

		/** The key for whatever variant [stack] is, ignoring how many of it there are. */
		fun of(stack: ItemStack): ItemKey = ItemKey(stack.asQuantity(1))

		/** The key for a registered Anion item. */
		fun of(item: AnionItem): ItemKey = of(item.asItemStack())

		/** The key for a plain vanilla item, in its default state. */
		fun of(type: ItemType): ItemKey = of(type.createItemStack())

		/** Rebuilds a key from [toBytes]. */
		fun fromBytes(bytes: ByteArray): ItemKey = of(ItemStack.deserializeBytes(bytes))

	}

}
