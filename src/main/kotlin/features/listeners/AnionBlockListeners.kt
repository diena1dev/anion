package dev.diena.anion.features.listeners

import com.destroystokyo.paper.event.server.ServerTickEndEvent
import dev.astralchroma.processor.annotations.Register
import dev.diena.anion.data.registry.AnionRegistryKey
import dev.diena.anion.data.registry.registries.AnionRegistries
import dev.diena.anion.extensions.toAnionItem
import dev.diena.anion.features.custom.blocks.AnionBlock
import dev.diena.anion.features.custom.blocks.AnionBlocks
import dev.diena.anion.features.custom.blocks.AnionMushroomCustomBlock
import dev.diena.anion.features.custom.blocks.MushroomType
import dev.diena.anion.features.custom.items.AnionNoteblockItem
import dev.diena.anion.features.custom.items.AnionMushroomBlockItem
import io.papermc.paper.event.player.PlayerPickBlockEvent
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Note
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.MultipleFacing
import org.bukkit.block.data.type.NoteBlock as NoteBlockData
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.block.BlockPhysicsEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockPistonEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.NotePlayEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot

// FIXME: cleanup and explain more of the listeners.
@Register
@Suppress("UnstableApiUsage")
object AnionBlockListeners : Listener {

    // helpers

    private fun noteData(block: Block) = block.blockData as? NoteBlockData

    private val MUSHROOM_FACES = listOf(
        BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    )

    private fun mushroomTypeOf(material: Material): MushroomType? = when (material) {
        Material.BROWN_MUSHROOM_BLOCK -> MushroomType.BROWN
        Material.RED_MUSHROOM_BLOCK -> MushroomType.RED
        else -> null
    }

    private fun mushroomData(block: Block) = block.blockData as? MultipleFacing

    private fun mushroomFaces(data: MultipleFacing): Set<BlockFace> =
        MUSHROOM_FACES.filterTo(mutableSetOf()) { data.hasFace(it) }

    private fun anionMushroomBlockAt(block: Block): AnionMushroomCustomBlock? {
        val type = mushroomTypeOf(block.type) ?: return null
        val data = mushroomData(block) ?: return null
        return AnionBlocks.fromMushroomState(type, mushroomFaces(data))
    }

    private fun anionBlockAt(block: Block): AnionBlock? =
        if (block.type == Material.NOTE_BLOCK) noteData(block)?.let { AnionBlocks.fromNoteblockState(it.instrument, it.note.id.toInt()) }
        else anionMushroomBlockAt(block)

    private fun simulateItemUse(event: PlayerInteractEvent) {
        val hand = event.hand ?: return
        val nmsHand = if (hand == EquipmentSlot.HAND) InteractionHand.MAIN_HAND else InteractionHand.OFF_HAND
        val nmsPlayer = (event.player as CraftPlayer).handle
        val nmsItem = nmsPlayer.getItemInHand(nmsHand)
        if (nmsItem.isEmpty) return

        val clicked = event.clickedBlock ?: return
        val nmsDir = when (event.blockFace) {
            BlockFace.UP    -> Direction.UP
            BlockFace.DOWN  -> Direction.DOWN
            BlockFace.NORTH -> Direction.NORTH
            BlockFace.SOUTH -> Direction.SOUTH
            BlockFace.EAST  -> Direction.EAST
            BlockFace.WEST  -> Direction.WEST
            else -> return
        }
        val point = event.interactionPoint
        val hitVec = if (point != null) Vec3(point.x, point.y, point.z)
                     else Vec3(clicked.x + 0.5, clicked.y + 0.5, clicked.z + 0.5)
        val hit = BlockHitResult(hitVec, nmsDir, BlockPos(clicked.x, clicked.y, clicked.z), false)
        val ctx = UseOnContext(nmsPlayer.level(), nmsPlayer, nmsHand, nmsItem, hit)
        nmsItem.useOn(ctx)
    }

    // helpers endregion

    // block place and break

    @EventHandler
    fun onBlockPlace(event: BlockPlaceEvent) {
        val block = event.blockPlaced

        if (block.type == Material.NOTE_BLOCK) {
            val data = noteData(block) ?: return
            val isAnionBlockItem = event.itemInHand.toAnionItem() is AnionNoteblockItem

            // Vanilla note block whose auto-detected instrument+note collides with a registered state:
            // reset note to first safe value so the placement doesn't create a phantom AnionBlock.
            if (!isAnionBlockItem && AnionBlocks.fromNoteblockState(data.instrument, data.note.id.toInt()) != null) {
                val safeNote = (0..24).firstOrNull { n -> AnionBlocks.fromNoteblockState(data.instrument, n) == null }
                if (safeNote != null) {
                    val fixed = data.clone() as NoteBlockData
                    fixed.note = Note(safeNote)
                    block.setBlockData(fixed, false)
                }
                return
            }
        }

        val mushroomType = mushroomTypeOf(block.type)
        if (mushroomType != null) {
            val data = mushroomData(block) ?: return
            val isAnionMushroomItem = event.itemInHand.toAnionItem() is AnionMushroomBlockItem
            val faces = mushroomFaces(data)

            // Vanilla mushroom block whose neighbor-derived face shape collides with a registered
            // state: reset to the first free state so the placement doesn't create a phantom AnionBlock.
            if (!isAnionMushroomItem && AnionBlocks.fromMushroomState(mushroomType, faces) != null) {
                val safeState = (1..64).firstOrNull { s ->
                    AnionBlocks.fromMushroomState(mushroomType, AnionMushroomCustomBlock.decodeState(s)) == null
                }
                if (safeState != null) {
                    val fixed = data.clone() as MultipleFacing
                    val safeFaces = AnionMushroomCustomBlock.decodeState(safeState)
                    MUSHROOM_FACES.forEach { face -> fixed.setFace(face, face in safeFaces) }
                    block.setBlockData(fixed, false)
                }
                return
            }
        }

        val anionBlock = anionBlockAt(block) ?: return
        anionBlock.onPlace(block, event.player)
    }

    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        val anionBlock = anionBlockAt(event.block) ?: return
        event.isDropItems = false
        if (event.player.gameMode == GameMode.CREATIVE) return



        val item = anionBlock.drops ?: AnionRegistries.ITEM_REGISTRY.getValue(AnionRegistryKey(anionBlock.namespacedKey.key))?.asItemStack()
        if (item != null) event.block.world.dropItem(event.block.location.toCenterLocation(), item)

        anionBlock.onBreak(event.block, event.player)
    }

    // block place and break end

    // interaction

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        val block = event.clickedBlock ?: return
        if (block.type != Material.NOTE_BLOCK) return
        val data = noteData(block) ?: return
        val note = data.note.id.toInt()

        val anionBlock = AnionBlocks.fromNoteblockState(data.instrument, note)
        if (anionBlock != null) {
            if (event.action == Action.RIGHT_CLICK_BLOCK) {
                // item usage defined by NMS, cancel paper events here
                event.setUseInteractedBlock(Event.Result.DENY)
                event.setUseItemInHand(Event.Result.DENY)
                anionBlock.onInteract(event)
                simulateItemUse(event)
            }
            return
        }

        // prevent right-click cycling into a registered AnionBlock
        if (event.action == Action.RIGHT_CLICK_BLOCK) {
            val nextNote = (note + 1) % 25
            if (AnionBlocks.fromNoteblockState(data.instrument, nextNote) != null) {
                event.isCancelled = true
            }
        }
    }

    @EventHandler
    fun onNotePlay(event: NotePlayEvent) {
        if (AnionBlocks.fromNoteblockState(event.instrument, event.note.id.toInt()) != null) {
            event.isCancelled = true
        }
    }

    // end interaction

    // physics protection

    @EventHandler
    fun onBlockPhysics(event: BlockPhysicsEvent) {
        val block = event.block

        if (block.type == Material.NOTE_BLOCK) {
            val data = noteData(block) ?: return
            val anionBlock = AnionBlocks.fromNoteblockState(data.instrument, data.note.id.toInt())
            if (anionBlock != null) {
                event.isCancelled = true
                anionBlock.onNeighborChange(block)
                return
            }

            val nextNote = (data.note.id.toInt() + 1) % 25
            if (AnionBlocks.fromNoteblockState(data.instrument, nextNote) != null) {
                event.isCancelled = true
                return
            }
        }

        val mushroomType = mushroomTypeOf(block.type)
        if (mushroomType != null) {
            val anionBlock = anionMushroomBlockAt(block)
            if (anionBlock != null) {
                // Prevent vanilla's neighbor-based face auto-connect from reshaping a registered state.
                event.isCancelled = true
                anionBlock.onNeighborChange(block)
                return
            }
        }

        if (anionBlockAt(block.getRelative(BlockFace.UP)) != null ||
            anionBlockAt(block.getRelative(BlockFace.DOWN)) != null) {
            event.isCancelled = true
        }
    }

    // physics protection end

    // piston protection

    private var blocksToFix = mutableMapOf<Block, BlockData>()

    @EventHandler
    fun onPistonExtend(event: BlockPistonExtendEvent) = trackPistonMove(event, event.blocks)

    @EventHandler
    fun onPistonRetract(event: BlockPistonRetractEvent) = trackPistonMove(event, event.blocks)

    private fun trackPistonMove(event: BlockPistonEvent, blocks: List<Block>) {
        for (block in blocks) {
            val destination = block.getRelative(event.direction)

            fun markIfAnion(target: Block, dataSource: Block = target) {
                val data = dataSource.blockData as? NoteBlockData ?: return
                if (AnionBlocks.fromNoteblockState(data.instrument, data.note.id.toInt()) == null) return
                blocksToFix[target] = data
            }

            markIfAnion(destination, block)               // the moving block itself
            markIfAnion(block.getRelative(BlockFace.UP))         // block above source
            markIfAnion(block.getRelative(BlockFace.DOWN))       // block below source
            markIfAnion(destination.getRelative(BlockFace.UP))   // block above destination
            markIfAnion(destination.getRelative(BlockFace.DOWN)) // block below destination
        }
    }

    @EventHandler
    fun onServerTickEnd(event: ServerTickEndEvent) {
        val toFix = blocksToFix
        blocksToFix = mutableMapOf()
        for ((block, data) in toFix) {
            block.setBlockData(data, false)
            block.world.players
                .filter { it.location.chunk == block.chunk }
                .forEach { it.sendBlockChange(block.location, data) }
        }
    }

    // piston protection end

    // pick block

    @EventHandler
    fun onPlayerPickBlock(event: PlayerPickBlockEvent) {
        if (event.block.type != Material.NOTE_BLOCK) return
        val anionBlock = anionBlockAt(event.block) ?: return
        event.isCancelled = true

        val registryItem = AnionRegistries.ITEM_REGISTRY.getValue(AnionRegistryKey(anionBlock.namespacedKey.key))
            as? AnionNoteblockItem ?: return
        val inventory = event.player.inventory

        var earliestEmpty: Int? = null

        for (slot in 0 until inventory.size) {
            val stack = inventory.getItem(slot)
            if (stack == null || stack.isEmpty) {
                if (earliestEmpty == null) earliestEmpty = slot
                continue
            }
            val found = (stack.toAnionItem() as? AnionNoteblockItem)?.anionBlock == anionBlock
            if (!found) continue

            if (slot < 9) {
                inventory.heldItemSlot = slot
                return
            }

            val emptyHotbar = earliestEmpty?.takeIf { it < 9 }
            if (emptyHotbar != null) {
                inventory.setItem(emptyHotbar, stack)
                inventory.setItem(slot, null)
                inventory.heldItemSlot = emptyHotbar
            } else {
                val held = inventory.heldItemSlot
                val displaced = inventory.getItem(held)
                inventory.setItem(held, stack)
                inventory.setItem(slot, displaced)
                if (earliestEmpty != null) {
                    inventory.setItem(earliestEmpty, displaced)
                    inventory.setItem(held, stack)
                }
            }
            event.player.updateInventory()
            return
        }

        if (event.player.gameMode != GameMode.CREATIVE) return

        val newStack = registryItem.asItemStack(1)
        if (earliestEmpty != null && earliestEmpty < 9) {
            inventory.setItem(earliestEmpty, newStack)
            inventory.heldItemSlot = earliestEmpty
        } else {
            val held = inventory.heldItemSlot
            val displaced = inventory.getItem(held)
            if (earliestEmpty != null) inventory.setItem(earliestEmpty, displaced)
            inventory.setItem(held, newStack)
        }
        event.player.updateInventory()
    }

    // pick block end

    // explosion

    private fun handleExplosionBlockList(blocks: MutableList<Block>) {
        val iter = blocks.iterator()

        while (iter.hasNext()) {
            val block = iter.next()
            val anionBlock = anionBlockAt(block) ?: continue

            val item = anionBlock.drops ?: AnionRegistries.ITEM_REGISTRY.getValue(AnionRegistryKey(anionBlock.namespacedKey.key))?.asItemStack()
            block.type = Material.AIR
            if (item != null) block.world.dropItem(block.location.toCenterLocation(), item)

            iter.remove()
            anionBlock.onBreak(block, null)
        }
    }

    // not entity explosions
    @EventHandler
    fun onBlockExplode(event: BlockExplodeEvent) = handleExplosionBlockList(event.blockList())

    // creepers, tnt
    @EventHandler
    fun onEntityExplode(event: EntityExplodeEvent) = handleExplosionBlockList(event.blockList())

    // explosion end

}
