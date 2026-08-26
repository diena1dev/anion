package dev.diena.anion.features.custom.blocks

import dev.diena.anion.Anion
import net.kyori.adventure.text.Component
import org.bukkit.Instrument
import org.bukkit.NamespacedKey
import org.bukkit.SoundGroup
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack

/**
 * A block a [dev.diena.anion.features.machine.component.MachineDisplay] can draw on.
 *
 * Horizontal facings only. A screen read from directly above or below is not a screen, and allowing it
 * would mean the text plane has a roll as well as a yaw — two more orientations for no readable surface.
 *
 * The block itself holds no text. It is the surface; the machine owns what is written on it.
 */
open class AnionDisplayBlock(

	displayName: String,
	instrument: Instrument,
	/** note per facing. all four horizontal faces, in the order north, east, south, west. */
	notesByFace: Map<BlockFace, Int>,
	stacksTo: Int = 64,
	styledDisplayName: Component = Component.text(displayName),
	namespacedKey: NamespacedKey = NamespacedKey(Anion.NAMESPACE, displayName.replace(" ", "_").lowercase()),
	drops: ItemStack? = null,
	soundGroup: SoundGroup? = null,

	placeHandler: ((block: Block, player: Player?) -> Unit)? = null,
	breakHandler: ((block: Block, player: Player?) -> Unit)? = null,
	interactHandler: ((event: PlayerInteractEvent) -> Boolean)? = null,
	neighborChangeHandler: ((block: Block) -> Unit)? = null,

) : AnionDirectionalBlock(

	displayName,
	instrument,
	notesByFace,
	stacksTo,
	styledDisplayName,
	namespacedKey,
	drops,
	soundGroup,
	placeHandler,
	breakHandler,
	interactHandler,
	neighborChangeHandler,

) {

	init {

		val vertical = notesByFace.keys.filter { it == BlockFace.UP || it == BlockFace.DOWN }
		if (vertical.isNotEmpty()) throw IllegalStateException("$namespacedKey is a display, so it cannot face $vertical")

	}

}
