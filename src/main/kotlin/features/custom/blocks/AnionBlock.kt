package dev.diena.anion.features.custom.blocks

import dev.diena.anion.Anion
import dev.diena.anion.features.custom.AnionResource
import net.kyori.adventure.text.Component
import net.minecraft.world.level.block.Rotation
import org.bukkit.Instrument
import org.bukkit.NamespacedKey
import org.bukkit.SoundGroup
import org.bukkit.Note
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.BlockType
import org.bukkit.block.data.type.NoteBlock
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack

open class AnionBlock(

	displayName: String,
	val instrument: Instrument,
	val note: Int,
	val stacksTo: Int = 64,
	val styledDisplayName: Component = Component.text(displayName),
	override val namespacedKey: NamespacedKey = NamespacedKey(Anion.NAMESPACE, displayName.replace(" ", "_").lowercase()),
	val drops: ItemStack? = null,
	/** sound this block places with. null follows the note block it is encoded as. */
	val soundGroup: SoundGroup? = null,

	private val placeHandler: ((block: Block, player: Player?) -> Unit)? = null,
	private val breakHandler: ((block: Block, player: Player?) -> Unit)? = null,
	private val interactHandler: ((event: PlayerInteractEvent) -> Boolean)? = null,
	private val neighborChangeHandler: ((block: Block) -> Unit)? = null,

) : AnionResource {

	companion object {
		private val internalBlock = BlockType.NOTE_BLOCK

		fun AnionBlock.getBlockState(): BlockState? {
			val block = internalBlock.createBlockData() as? NoteBlock
			block?.note = Note(this.note)
			block?.instrument = instrument

			return block?.createBlockState()
		}
	}

	init {
		if (note !in 0..24) throw IllegalStateException("note must be 0–24, got $note for ${this.namespacedKey}")
	}

	/**
	 * The note this block should carry after [rotation]. Defaults to unchanged.
	 *
	 * A note block has no rotatable vanilla property, so anything an anion block encodes in its note
	 * has to be turned here or it survives a starship rotation pointing the way it started.
	 */
	open fun noteAfterRotation(note: Int, rotation: Rotation): Int = note

	open fun onPlace(block: Block, player: Player?) { placeHandler?.invoke(block, player) }
	open fun onBreak(block: Block, player: Player?) { breakHandler?.invoke(block, player) }
	/**
	 * Handles a right click on this block. Returns whether the click was spent.
	 *
	 * False leaves the click to the placement path, so a block that does nothing with it is still
	 * something you can build against.
	 */
	open fun onInteract(event: PlayerInteractEvent): Boolean = interactHandler?.invoke(event) ?: false
	open fun onNeighborChange(block: Block) { neighborChangeHandler?.invoke(block) }
	open fun onAdd() {}
	open fun onRemove() {}

}
