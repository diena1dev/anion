package dev.diena.anion.features.custom.blocks

import dev.diena.anion.Anion
import net.kyori.adventure.text.Component
import org.bukkit.Instrument
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack

abstract class AnionBlock(

    displayName: String,
    val styledDisplayName: Component = Component.text(displayName),
    val namespacedKey: NamespacedKey = NamespacedKey(Anion.NAMESPACE, displayName.replace(" ", "_").lowercase()),
    val drops: ItemStack? = null,

    private val placeHandler: ((block: Block, player: Player?) -> Unit)? = null,
    private val breakHandler: ((block: Block, player: Player?) -> Unit)? = null,
    private val interactHandler: ((event: PlayerInteractEvent) -> Unit)? = null,
    private val neighborChangeHandler: ((block: Block) -> Unit)? = null,

) {

    open fun onPlace(block: Block, player: Player?) { placeHandler?.invoke(block, player) }
    open fun onBreak(block: Block, player: Player?) { breakHandler?.invoke(block, player) }
    open fun onInteract(event: PlayerInteractEvent) { interactHandler?.invoke(event) }
    open fun onNeighborChange(block: Block) { neighborChangeHandler?.invoke(block) }
    open fun onAdd() {}
    open fun onRemove() {}

}
