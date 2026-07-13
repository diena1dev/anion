package dev.diena.anion.features.custom.blocks

import dev.diena.anion.Anion
import net.kyori.adventure.text.Component
import org.bukkit.Instrument
import org.bukkit.NamespacedKey
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
    val namespacedKey: NamespacedKey = NamespacedKey(Anion.NAMESPACE, displayName.replace(" ", "_").lowercase()),
    val drops: ItemStack? = null,

    private val placeHandler: ((block: Block, player: Player?) -> Unit)? = null,
    private val breakHandler: ((block: Block, player: Player?) -> Unit)? = null,
    private val interactHandler: ((event: PlayerInteractEvent) -> Unit)? = null,
    private val neighborChangeHandler: ((block: Block) -> Unit)? = null,
) {

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

    open fun onPlace(block: Block, player: Player?) { placeHandler?.invoke(block, player) }
    open fun onBreak(block: Block, player: Player?) { breakHandler?.invoke(block, player) }
    open fun onInteract(event: PlayerInteractEvent) { interactHandler?.invoke(event) }
    open fun onNeighborChange(block: Block) { neighborChangeHandler?.invoke(block) }
    open fun onAdd() {}
    open fun onRemove() {}
}
