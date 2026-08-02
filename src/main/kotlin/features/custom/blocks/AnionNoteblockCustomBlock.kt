package dev.diena.anion.features.custom.blocks

import org.bukkit.Instrument
import org.bukkit.inventory.ItemStack

class AnionNoteblockCustomBlock(

	displayName: String,
	val instrument: Instrument,
	val note: Int,
	val stacksTo: Int = 64,
	drops: ItemStack? = null,

) : AnionBlock(displayName, drops = drops) {

	init {
		if (note !in 0..24) throw IllegalStateException("note must be 0–24, got $note for ${this.namespacedKey}")
	}

}
