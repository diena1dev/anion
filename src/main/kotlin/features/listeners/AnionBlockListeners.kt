package dev.diena.anion.features.listeners

import com.destroystokyo.paper.event.server.ServerTickEndEvent
import dev.astralchroma.processor.annotations.Register
import dev.diena.anion.data.registry.AnionRegistryKey
import dev.diena.anion.data.registry.registries.AnionRegistries
import dev.diena.anion.extensions.toAnionItem
import dev.diena.anion.extensions.axis
import dev.diena.anion.extensions.vec3i
import dev.diena.anion.features.custom.blocks.AnionBlock
import dev.diena.anion.features.custom.blocks.AnionBlocks
import dev.diena.anion.features.custom.blocks.AnionPillarBlock
import dev.diena.anion.features.custom.items.AnionBlockItem
import dev.diena.anion.features.machine.MachineIndex
import dev.diena.anion.features.starship.Starship
import dev.diena.anion.features.transport.AnionTransportIndex
import org.bukkit.craftbukkit.CraftWorld
import io.papermc.paper.event.player.PlayerPickBlockEvent
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import org.bukkit.Axis
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Note
import org.bukkit.SoundCategory
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.NoteBlock
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Player
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
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.inventory.EquipmentSlot
import java.util.WeakHashMap

// FIXME: cleanup and explain more of the listeners.
@Register
@Suppress("UnstableApiUsage")
object AnionBlockListeners : Listener {

	// helpers

	private fun noteData(block: Block) = block.blockData as? NoteBlock

	private fun anionBlockAt(block: Block) =
		if (block.type != Material.NOTE_BLOCK) null
		else noteData(block)?.let { AnionBlocks.fromState(it.instrument, it.note.id.toInt()) }

	/** queues any machine holding this cell for a structure re-check. vanilla blocks count — machines use them too. */
	// block events fire while the block is still in the world, so this only queues. the drain on the
	// next slow tick is what actually reads the world back.
	private fun markMachineCell(block: Block) {
		val level = (block.world as CraftWorld).handle
		val vec = block.vec3i

		MachineIndex.markChanged(level, vec)

		// carried machines are not in the cell index — their ship holds the lookup instead
		Starship.starshipAt(level, vec)?.machines?.machinesHolding(vec)?.forEach { MachineIndex.markChanged(it) }
	}

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
		val useContext = UseOnContext(nmsPlayer.level(), nmsPlayer, nmsHand, nmsItem, hit)

		// the cell a placement would land in, so we can tell whether one actually happened
		val target = clicked.getRelative(event.blockFace)
		val occupiedBefore = anionBlockAt(target)

		nmsItem.useOn(useContext)

		val placed = anionBlockAt(target) ?: return
		if (occupiedBefore != null) return

		playPlaceSound(target, placed, event.player)
	}

	/** Plays [anionBlock]'s place sound to [player], who would otherwise not hear it. */
	// BlockItem.place() plays the sound with the placer excluded, because vanilla expects their client
	// to have predicted it. this placement is driven server-side, so without this it is silent for the
	// one person who should definitely hear it.
	private fun playPlaceSound(block: Block, anionBlock: AnionBlock, player: Player) {

		val group = anionBlock.soundGroup ?: block.blockData.soundGroup

		player.playSound(
			block.location.toCenterLocation(),
			group.placeSound,
			SoundCategory.BLOCKS,
			group.volume,
			group.pitch,
		)

	}

	/** Lays a freshly placed pillar block along the axis the placement implies. */
	private fun layOnPlacement(block: Block, pillar: AnionPillarBlock, event: BlockPlaceEvent) {

		val note = pillar.noteFor(placementAxis(pillar, event)) ?: return
		val data = noteData(block) ?: return

		data.note = Note(note)
		block.setBlockData(data, false) // no physics: the note swap is not a world change worth firing on

	}

	/** The face each player last clicked, since [BlockPlaceEvent] does not carry one. */
	// weak keys so a player who logs out drops out on their own, without a quit handler to remember
	private val lastClickedFace = WeakHashMap<Player, BlockFace>()

	/** The axis a pillar lands on: the one running out of the face that was clicked. */
	// this also extends a run for free. clicking the end of a pipe gives the same axis, clicking its
	// side gives the perpendicular one, so branching and continuing are the same gesture.
	private fun placementAxis(pillar: AnionPillarBlock, event: BlockPlaceEvent): Axis {

		// the delta only agrees with the clicked face when the placement landed in a cell of its own.
		// a placement that replaces grass or water lands *in* the block it was placed against, making
		// the delta SELF, so the remembered face is what carries those.
		val clickedFace = lastClickedFace[event.player] ?: event.blockAgainst.getFace(event.blockPlaced)

		return pillar.axisFor(clickedFace)

	}

	// helpers endregion

	// block place and break

	@EventHandler
	fun onBlockPlace(event: BlockPlaceEvent) {
		val block = event.blockPlaced

		markMachineCell(block) // a replaced cell can repair a broken machine
		AnionTransportIndex.register(block) // no-op unless it is a pipe, chute or crafting table

		if (block.type == Material.NOTE_BLOCK) {
			val data = noteData(block) ?: return
			val isAnionBlockItem = event.itemInHand.toAnionItem() is AnionBlockItem

			// Vanilla note block whose auto-detected instrument+note collides with a registered state:
			// reset note to first safe value so the placement doesn't create a phantom AnionBlock.
			if (!isAnionBlockItem && AnionBlocks.fromState(data.instrument, data.note.id.toInt()) != null) {
				val safeNote = (0..24).firstOrNull { note -> AnionBlocks.fromState(data.instrument, note) == null }
				if (safeNote != null) {
					val fixed = data.clone() as NoteBlock
					fixed.note = Note(safeNote)
					block.setBlockData(fixed, false)
				}
				return
			}
		}

		val anionBlock = anionBlockAt(block) ?: return

		if (anionBlock is AnionPillarBlock) layOnPlacement(block, anionBlock, event)

		anionBlock.onPlace(block, event.player)
	}

	@EventHandler
	fun onBlockBreak(event: BlockBreakEvent) {
		markMachineCell(event.block)
		AnionTransportIndex.unregister(event.block)

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
		// before any of the note block filtering below: a pillar placed against plain dirt needs this
		// face just as much as one placed against an anion block, and BlockPlaceEvent will not have it
		if (event.action == Action.RIGHT_CLICK_BLOCK) lastClickedFace[event.player] = event.blockFace

		val block = event.clickedBlock ?: return
		if (block.type != Material.NOTE_BLOCK) return
		val data = noteData(block) ?: return
		val note = data.note.id.toInt()

		val anionBlock = AnionBlocks.fromState(data.instrument, note)
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
			if (AnionBlocks.fromState(data.instrument, nextNote) != null) {
				event.isCancelled = true
			}
		}
	}

	@EventHandler
	fun onNotePlay(event: NotePlayEvent) {
		if (AnionBlocks.fromState(event.instrument, event.note.id.toInt()) != null) {
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
			val anionBlock = AnionBlocks.fromState(data.instrument, data.note.id.toInt())
			if (anionBlock != null) {
				event.isCancelled = true
				anionBlock.onNeighborChange(block)
				return
			}

			val nextNote = (data.note.id.toInt() + 1) % 25
			if (AnionBlocks.fromState(data.instrument, nextNote) != null) {
				event.isCancelled = true
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
				val data = dataSource.blockData as? NoteBlock ?: return
				if (AnionBlocks.fromState(data.instrument, data.note.id.toInt()) == null) return
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
			as? AnionBlockItem ?: return
		val inventory = event.player.inventory

		var earliestEmpty: Int? = null

		for (slot in 0 until inventory.size) {
			val stack = inventory.getItem(slot)
			if (stack == null || stack.isEmpty) {
				if (earliestEmpty == null) earliestEmpty = slot
				continue
			}
			val found = (stack.toAnionItem() as? AnionBlockItem)?.anionBlock == anionBlock
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
			markMachineCell(block)
			AnionTransportIndex.unregister(block)

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

	// transport index paging

	@EventHandler
	fun onChunkLoad(event: ChunkLoadEvent) {
		AnionTransportIndex.loadChunk(event.world, event.chunk.x, event.chunk.z)
	}

	@EventHandler
	fun onChunkUnload(event: ChunkUnloadEvent) {
		AnionTransportIndex.unloadChunk(event.world, event.chunk.x, event.chunk.z)
	}

	// transport index paging end

}
