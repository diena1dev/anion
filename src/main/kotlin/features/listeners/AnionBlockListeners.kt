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
				// only place if the block did not spend the click on something of its own
				if (!anionBlock.onInteract(event)) simulateItemUse(event)
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

	// block state protection

	/** blockdata that belongs in a cell once the tick's block churn has settled */
	// moved says the block arrived in this cell rather than never leaving it, which is what decides
	// whether the indexes need telling even when the data turns out not to have been mangled at all
	private class PendingFix(val data: BlockData, val moved: Boolean, var ticksWaited: Int = 0)

	private val blocksToFix = mutableMapOf<Block, PendingFix>()

	/**
	 * Cells a piston is rewriting, and how many ticks they stay exempt for.
	 *
	 * The neighbour rule below must not touch these. A vertical piston sits directly under the anion
	 * block it is moving, so the rule fires on the piston itself and suppresses the shape propagation
	 * the move is carried by — which is why moving one sideways always worked and vertically never did.
	 *
	 * It has to outlive the tick the move started in. The head only lands when the slide finishes two
	 * ticks later, into the cell right under the block that was just pushed off it, and the rule fires
	 * on that too — which is why extending broke after retracting was fixed.
	 */
	private val movingCells = mutableMapOf<Block, Int>()

	/** ticks a fix waits for a piston to finish sliding before it is applied regardless */
	private const val SETTLE_TICKS = 4

	/** The note block data at [block] when it encodes a registered AnionBlock, else null. */
	private fun anionNoteData(block: Block): NoteBlock? {
		val data = block.blockData as? NoteBlock ?: return null
		if (AnionBlocks.fromState(data.instrument, data.note.id.toInt()) == null) return null

		return data
	}

	/** Queues [data] to be stamped back into [block] once the tick has settled. */
	private fun repair(block: Block, data: BlockData, moved: Boolean = false) {
		blocksToFix[block] = PendingFix(data, moved)
	}

	/** Leaves [block] out of the neighbour rule until the piston move touching it has finished. */
	private fun exempt(block: Block) {
		movingCells[block] = SETTLE_TICKS
	}

	@EventHandler
	fun onBlockPhysics(event: BlockPhysicsEvent) {

		val block = event.block

		if (block.type == Material.NOTE_BLOCK) {

			// an anion block is inert: no repowering, no note cycling, no instrument recalculation
			val data = noteData(block) ?: return
			val anionBlock = AnionBlocks.fromState(data.instrument, data.note.id.toInt())
			if (anionBlock != null) {
				event.isCancelled = true
				anionBlock.onNeighborChange(block)
				return
			}

			// a vanilla note block one note short of a registered state must not be cycled into it
			val nextNote = (data.note.id.toInt() + 1) % 25
			if (AnionBlocks.fromState(data.instrument, nextNote) != null) {
				event.isCancelled = true
				return
			}

			// deliberately falls through. a vanilla note block beside an anion block is the case the
			// rule below exists for, and returning here is what let it cascade.

		}

		// a cell a piston is rewriting right now is the one exception: the move is carried by the shape
		// propagation the rule below suppresses. the anion blocks involved are repaired at tick end by
		// trackPistonMove instead, which is a better guarantee than the rule was giving them anyway.
		if (block in movingCells) return

		// this block just changed, and NoteBlock.updateShape recomputes the instrument from whatever is
		// on its Y axis. an anion instrument is one vanilla only ever produces from a mob head above, so
		// a single shape update resets it and deregisters the block.
		//
		// cancelling here is what stops that: Level.notifyAndUpdatePhysics only skips
		// updateNeighbourShapes and updateIndirectNeighbourShapes when the event is cancelled, and it
		// has already run updateNeighborsAt by that point. so this suppresses shape propagation out of
		// this cell and costs its own behaviour nothing — a repeater or a hopper beside a machine still
		// gets every neighbourChanged it needs. do not exempt block *types* from this.
		if (anionBlockAt(block.getRelative(BlockFace.UP)) != null ||
			anionBlockAt(block.getRelative(BlockFace.DOWN)) != null) {
			event.isCancelled = true
		}

	}

	@EventHandler
	fun onPistonExtend(event: BlockPistonExtendEvent) = trackPistonMove(event, event.blocks)

	@EventHandler
	fun onPistonRetract(event: BlockPistonRetractEvent) = trackPistonMove(event, event.blocks)

	/** Records the anion data a piston move is about to lose, keyed by the cell it belongs in after. */
	private fun trackPistonMove(event: BlockPistonEvent, blocks: List<Block>) {

		// every cell the move empties. on a vertical move these are also the up/down neighbours swept
		// below, and stamping one is what duplicated the block: the piston head lands in a vacated cell
		// on extend, and on retract it is left as plain air with a second copy of an already-moved block.
		val vacated = blocks.toHashSet()

		// the whole footprint of the move, anion or not, so onBlockPhysics leaves all of it alone.
		// both sides of the piston, since the head lands in front on an extend and vacates it on a retract
		exempt(event.block)
		exempt(event.block.getRelative(event.direction))
		exempt(event.block.getRelative(event.direction.oppositeFace))

		for (block in blocks) {
			exempt(block)
			exempt(block.getRelative(event.direction))
		}

		val destinations = mutableMapOf<Block, BlockData>()
		for (block in blocks) {
			val data = anionNoteData(block) ?: continue
			destinations[block.getRelative(event.direction)] = data
		}

		// a note block reads its instrument off the block below it, so a cell that changes can mangle
		// the anion block above or below it. those are not moving, so they get repaired where they are.
		for (block in blocks) {

			val destination = block.getRelative(event.direction)

			val neighbours = listOf(
				block.getRelative(BlockFace.UP),
				block.getRelative(BlockFace.DOWN),
				destination.getRelative(BlockFace.UP),
				destination.getRelative(BlockFace.DOWN),
			)

			for (neighbour in neighbours) {
				if (neighbour in vacated || neighbour in destinations) continue

				val data = anionNoteData(neighbour) ?: continue
				repair(neighbour, data)
			}

		}

		// last, so a moving block's destination outranks any repair another block claimed for that cell
		for ((destination, data) in destinations) repair(destination, data, moved = true)

	}

	@EventHandler
	fun onServerTickEnd(event: ServerTickEndEvent) {

		// exemptions age out on their own, so a move that never lands cannot leave one behind
		if (movingCells.isNotEmpty()) {
			val expiring = movingCells.entries.iterator()
			while (expiring.hasNext()) {
				val entry = expiring.next()
				if (entry.value <= 1) expiring.remove() else entry.setValue(entry.value - 1)
			}
		}

		if (blocksToFix.isEmpty()) return

		val pending = blocksToFix.entries.iterator()

		while (pending.hasNext()) {

			val (block, fix) = pending.next()

			// the piston is still sliding this cell. stamping now replaces the moving-piston block entity
			// mid-slide, and that block entity is what shoves entities along in front of the block.
			if (block.type == Material.MOVING_PISTON && fix.ticksWaited < SETTLE_TICKS) {
				fix.ticksWaited++
				continue
			}

			pending.remove()

			// by far the common case: a physics repair queued beside a machine that nothing went on to
			// mangle. every redstone tick next to one of these queues a fix, so a no-op must stay a no-op
			// rather than rewriting the block and telling everyone in view distance about it.
			if (!fix.moved && block.blockData == fix.data) continue

			block.setBlockData(fix.data, false)

			// a pushed block lives somewhere else now. the cell it left is a stale hint and drops out on
			// the next read, but nothing would ever have discovered the one it landed in.
			AnionTransportIndex.register(block)
			markMachineCell(block)

			// everyone who can see the cell, not only whoever is standing in its chunk — the rest were
			// left holding whatever the piston told them was there
			val viewRadius = block.world.viewDistance * 16.0
			block.world.players
				.filter { it.location.distanceSquared(block.location) <= viewRadius * viewRadius }
				.forEach { it.sendBlockChange(block.location, fix.data) }

		}

	}

	// block state protection end

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
