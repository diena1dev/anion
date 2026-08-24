package dev.diena.anion.features.machine

import dev.diena.anion.Anion
import dev.diena.anion.Tasks
import dev.diena.anion.data.database.AnionPersistence
import dev.diena.anion.data.registry.AnionRegistryKey
import dev.diena.anion.data.registry.registries.AnionRegistries
import dev.diena.anion.extensions.minus
import dev.diena.anion.extensions.plus
import dev.diena.anion.extensions.rotate
import dev.diena.anion.features.custom.AnionResource
import dev.diena.anion.features.custom.ItemKey
import dev.diena.anion.features.custom.items.AnionItem
import dev.diena.anion.features.machine.component.MachineBuffer
import dev.diena.anion.features.machine.component.MachinePort
import dev.diena.anion.features.starship.Starship
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Rotation
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/// Machine member inventory — every variable and function of the base class,
/// grouped by who supplies it and who is allowed to touch it.
//|
//| [CONSTRUCTOR — type config. val, immutable. one set of values per registered machine type]
//|- displayName: String
//|- blockSet: BlockSet?                                 // structure definition, compared at origin+rotation.
//|                                                      // null for machines that don't describe themselves as a
//|                                                      // fixed block layout — they own isIntact() instead
//|- namespacedKey: NamespacedKey = adaptedFromDisplayName // AnionResource impl, used as machine-type registry key
//|
//| [INSTANCE STATE — var, owned by base. written ONLY by assemble()/load()/pipeline, subclasses read]
//|- uuid: UUID                  lateinit                // identity in activeMachines + database
//|- level: ServerLevel          lateinit
//|- origin: Vec3i               lateinit                // core block world pos, rotation pivot
//|- rotation: Rotation          = NONE                  // solved during assembly wrench-check
//|- resolvedStructure           = mutableMapOf()        // the variant that actually filled each cell at assembly.
//|                                                      // frozen until reassembly: a cell only satisfies the
//|                                                      // structure again if THIS block comes back
//|- buffers                     = mutableMapOf()        // resource stores. survive a broken structure,
//|                                                      // destroyed on disassembly
//|- intact: Boolean             = false; protected set  // cached structure result, gates tick(). written by
//|                                                      // activate() and revalidate()
//|- dirty: Boolean              = false                 // persistence flag, cleared on save. means
//|                                                      // "origin/rotation/structure changed", not "ticked"
//|- starship: Starship?         = null; internal set    // carrier ship, null if grounded. owned by StarshipMachines
//|
//| TODO: data channels. a machine sends a signal along a line and whatever grabs that signal can use
//|       it — a mainframe picks the signal up and reroutes it based on the config stored for that
//|       signature. not a map of channels hanging off the machine.
//|
//| [FINAL PIPELINE — fun, base implements, nobody overrides]
//|- assemble(level, origin, rotation): Machine?   // resolve structure, register, persist, onAssemble(). null if it doesn't fit
//|- load(uuid, level, origin, rotation, tag): Machine
//|                              // db restore: bind, loadFrom(tag), register, onAssemble()
//|- saveTo(tag)                 // full payload: frozen structure + buffer contents + saveState(). serializer-only
//|- disassemble()               // onDisassemble(), spill buffers, detach from carrier, deregister, delete save
//|- relocate(newOrigin, addedRotation)  // ship-driven move/rotate, then onRelocate()
//|- revalidate()                // structure re-check. tears the machine down past DISASSEMBLY_THRESHOLD
//|- runTick()                   // ticker entry: bail if carrier is mid-move, else if (intact) tick()
//|- runSlowTick()               // ticker entry: bail if carrier is mid-move, else if (intact) slowTick()
//|
//| [PROTECTED UTILS — fun, subclasses call, never override]
//|- localToWorld(offset): Vec3i                         // origin-relative, rotation-applied
//|- blockDataAt(offset): BlockData                      // world read at local offset
//|- setBlockAt(offset, blockData)                       // committed sync
//|- markDirty()
//|
//| [HOOKS — subclasses implement/override]
//|- tick()             abstract  every game tick, main thread, only runs while intact
//|- slowTick()         abstract  every second, main thread, only runs while intact
//|- isIntact(): Boolean open     fail-fast check against the frozen structure. allocation free — this is
//|                               the one the tick gate uses. override for blockSet-less machines
//|- structureResult()  open      full check: also reports which cells are broken. allocates, so it only
//|                               runs on revalidate(). override alongside isIntact()
//|- onStructureChanged(result) open  ran after every revalidate(). tank drain, break particles
//|- onAssemble()       open      extra init not covered by pipeline
//|- onDisassemble()    open      extra teardown: flush recipes, shutdown attached entities
//|- onRelocate()       open      fix up cached absolute positions after a ship moved this machine
//|- saveState(tag)     open      write mutable state that cannot be re-derived (progress, extension, ...)
//\- loadState(tag)     open      read it back. runs after bind(), before registration
//
// subclass adds ONLY: (1) family config as constructor vals (doorWidth, doorMaterial, ...)
//                     (2) mutable behavior state (extension: Int, progress: Int, ...)
// never add: cached world blocks, values derivable from blockSet, or anything a component owns


/** Outcome of a structure check: whether every cell still matches, and which ones do not. */
data class StructureResult(

	val intact: Boolean,
	val brokenOffsets: Set<Vec3i>,

)

/** A machine type that fully matched the world, and where it matched. */
data class AssemblyCandidate(

	val factory: () -> Machine,
	val origin: Vec3i,
	val rotation: Rotation,

)

// open is can override
// abstract is must override
// fun is a static callback that cannot be changed
// open functions can have super calls that still use the original logic + whatever other things you add
/** IMPORTANT: **Do not** access any lateinit vars from outside of functions. */
abstract class Machine(

	displayName: String,    // self-explanatory
	val blockSet: BlockSet?,    // fine to have in 90% of machines, blockSet doesn't have to be used by anything since the machine handles intact logic

	override val namespacedKey: NamespacedKey = NamespacedKey(Anion.NAMESPACE, displayName.lowercase().replace(' ', '_')),

) : AnionResource {

	companion object {

		/** List of all loaded machines, tick list */
		// TODO: move to per-chunk/load-balancing ticking so all machines don't try to tick at once
		val activeMachines: ConcurrentHashMap<UUID, Machine> = ConcurrentHashMap()

		/** fraction of mismatched cells past which a broken machine tears itself down instead of waiting for repair */
		const val DISASSEMBLY_THRESHOLD = 0.5

		/** what a machine will move per transport pass when its type does not say otherwise */
		const val DEFAULT_TRANSFER_CEILING = 512L

		/** Every machine holding [cell]. Machines may share cells, so this is a list. */
		// grounded machines live in the cell index; carried ones are tracked by their ship instead
		fun machinesAt(level: ServerLevel, cell: Vec3i): List<Machine> =
			(MachineIndex.machinesAt(level, cell) +
				Starship.starshipAt(level, cell)?.machines?.machinesHolding(cell).orEmpty()).distinct()

		/**
		 * Picks the declared variant that actually fills every cell of [blockSet] at [origin]+[rotation].
		 * Returns null if any cell is unmatched.
		 */
		fun resolveStructure(

			blockSet: BlockSet,
			level: ServerLevel,
			origin: Vec3i,
			rotation: Rotation,

		): Map<Vec3i, BlockMatcher>? {

			val resolved = HashMap<Vec3i, BlockMatcher>(blockSet.blockMap.size)

			for ((offset, variants) in blockSet.blockMap) {

				val worldPos = origin + offset.rotate(rotation)
				if (!level.world.isChunkLoaded(worldPos.x shr 4, worldPos.z shr 4)) return null

				val blockData = level.world.getBlockAt(worldPos.x, worldPos.y, worldPos.z).blockData
				val matched = variants.firstOrNull { it.matches(blockData) } ?: return null

				resolved[offset] = matched

			}

			return resolved

		}

		/**
		 * Every registered machine type that fully matches the world with [clickedPosition] as one of its
		 * cells. Empty when nothing matches, more than one entry when the placement is ambiguous.
		 */
		// the clicked block is the anchor, not the core — a player wrenches whatever face they can reach,
		// so every cell that block could be filling gets tried as the anchor in every rotation.
		fun candidatesAt(

			level: ServerLevel,
			clickedPosition: Vec3i,

		): List<AssemblyCandidate> {

			val clickedData = level.world
				.getBlockAt(clickedPosition.x, clickedPosition.y, clickedPosition.z)
				.blockData

			val candidates = mutableListOf<AssemblyCandidate>()

			for (factory in AnionMachines.all) {

				val prototype = factory()
				val blockSet = prototype.blockSet ?: continue

				// cells the clicked block could be filling. rotation-independent, so it is solved once
				val anchorOffsets = blockSet.blockMap
					.filterValues { variants -> variants.any { it.matches(clickedData) } }
					.keys

				if (anchorOffsets.isEmpty()) continue

				val coreVariants = blockSet.blockMap[Vec3i.ZERO]
				val matchedOrigins = mutableSetOf<Vec3i>()
				val matchedFootprints = mutableSetOf<Set<Vec3i>>()

				for (rotation in Rotation.entries) for (anchorOffset in anchorOffsets) {

					val origin = clickedPosition - anchorOffset.rotate(rotation)

					// a rotationally symmetric structure matches at the same origin under several
					// rotations — that is one machine, not an ambiguous pile of them
					if (origin in matchedOrigins) continue

					// this structure is already assembled — wrenching it again would duplicate it
					if (activeMachines.values.any {
							it.level == level && it.origin == origin && it.namespacedKey == prototype.namespacedKey
						}) continue

					// cheap reject: the core cell is one world read and kills nearly every candidate
					if (coreVariants != null) {
						if (!level.world.isChunkLoaded(origin.x shr 4, origin.z shr 4)) continue

						val coreData = level.world.getBlockAt(origin.x, origin.y, origin.z).blockData
						if (coreVariants.none { it.matches(coreData) }) continue
					}

					val resolved = resolveStructure(blockSet, level, origin, rotation) ?: continue

					// a structure whose core sits off-centre in a symmetric shape matches at a *different*
					// origin per rotation, all covering the same blocks. same machine, counted once.
					val footprint = resolved.keys.mapTo(HashSet()) { origin + it.rotate(rotation) }
					if (!matchedFootprints.add(footprint)) continue

					matchedOrigins += origin
					candidates += AssemblyCandidate(factory, origin, rotation)

				}

			}

			return candidates

		}

	}

	lateinit var uuid: UUID
	lateinit var level: ServerLevel
	lateinit var origin: Vec3i

	/** the variant that actually filled each cell at assembly. frozen until the machine is reassembled. */
	val resolvedStructure: MutableMap<Vec3i, BlockMatcher> = mutableMapOf()

	/** resource stores, keyed by name. kept while the structure is broken, spilled on disassembly. */
	val buffers: MutableMap<String, MachineBuffer> = mutableMapOf()

	/**
	 * Ceiling on units moved in or out of this machine across one transport pass, summed over every
	 * buffer.
	 *
	 * A port raises the rate of the buffer it is bound to; this is the only thing that stops that
	 * scaling forever. Machine-wide rather than per-buffer on purpose — a per-buffer cap would just be
	 * dodged by spreading the same wall of ports over three buffers instead of one.
	 */
	open val transferCeiling: Long = DEFAULT_TRANSFER_CEILING

	var rotation: Rotation = Rotation.NONE; protected set
	var intact: Boolean = false; protected set
	var dirty = false; internal set // cleared by AnionPersistence once the machine is written

	/** ship this Machine's core block sits on, null if grounded. owned by [StarshipMachines]. */
	// origin stays absolute even while attached — localToWorld() runs per-block per-isIntact(), so it
	// must never have to resolve through the ship. the ship pushes transforms down via relocate().
	var starship: Starship? = null; internal set

	/** chunks this machine's cells fall in, packed. recomputed on assembly and relocation. */
	private var occupiedChunks: Set<Long> = emptySet()

	/** buffer contents read back from disk, applied once the type has declared its buffers */
	private var restoredBuffers: Map<String, Map<AnionResource, Long>> = emptyMap()

	/** Called every game tick, main thread. */
	abstract fun tick()
	/** Called every second, main thread. Agnostic of game tickrate, be wary of world state desyncs. */
	abstract fun slowTick()

	/** Fail-fast structure check against the frozen [resolvedStructure]. */
	// allocation free, so the tick gate can call it freely. override for machines with no blockSet.
	open fun isIntact(): Boolean {

		blockSet ?: return true
		if (resolvedStructure.isEmpty()) return false // a structured machine that resolved nothing is not a machine

		return resolvedStructure.all { (offset, matcher) -> matcher.matches(blockDataAt(offset)) }

	}

	/** Full structure check: also reports which cells no longer hold the block they were assembled with. */
	// allocates, so this only runs on revalidate(). broken offsets drive tank draining and break particles.
	open fun structureResult(): StructureResult {

		blockSet ?: return StructureResult(true, emptySet())
		if (resolvedStructure.isEmpty()) return StructureResult(false, emptySet())

		val brokenOffsets = mutableSetOf<Vec3i>()
		for ((offset, matcher) in resolvedStructure) {
			if (!matcher.matches(blockDataAt(offset))) brokenOffsets += offset
		}

		return StructureResult(brokenOffsets.isEmpty(), brokenOffsets)

	}

	/** Called when a player right-clicks a sign in this machine's structure. */
	// recommended use: open a menu, step a page, flip a setting. machine signs are never editable by
	// hand, so a click is the only thing a player can do to one — see [MachineSign]
	open fun onSignClick(offset: Vec3i, player: Player, front: Boolean) {

	}

	/** Called after every structure re-check, intact or not. */
	// recommended use: drain a tank through its broken walls, emit particles at the offsets that went missing
	open fun onStructureChanged(result: StructureResult) {

	}

	/** Extra lines for [debugReport] — whatever this type wants a look at that the base cannot know. */
	open fun debugLines(): List<String> = emptyList()

	/** The port section of [debugReport]. Empty unless the type has ports. */
	protected open fun portLines(): List<String> = emptyList()

	/** Everything worth knowing about this machine, one line at a time. */
	// shared by /machine debug and the wrench, so the two can never drift apart
	fun debugReport(): List<String> {

		val lines = mutableListOf<String>()

		val carrier = starship?.uuid?.toString()?.take(8) ?: "none"

		lines += "${namespacedKey.key} @ $origin rot=$rotation intact=$intact ship=$carrier"
		lines += "  cells=${resolvedStructure.size} dirty=$dirty"

		// the per-buffer rates below are what ports bought; this is what the machine will actually allow
		lines += "  transfer ceiling=$transferCeiling/pass across all buffers"

		lines += portLines()

		if (buffers.isEmpty()) lines += "  buffers: none"
		else for (buffer in buffers.values) {

			lines += "  buffer ${buffer.describe()}"

			for ((resource, amount) in buffer.contents()) lines += "   - ${resource.namespacedKey} x$amount"

		}

		for (line in debugLines()) lines += "  $line"

		return lines

	}

	/** Called on Machine Disassembly */
	// recommended use: flush recipes, call shutdown() to any attached entities or display components.
	// buffers are spilled by the base right after this runs.
	open fun onDisassemble() {

	}

	/** Called on Machine Assembly */
	// recommended use: declare buffers, bind ports off resolvedStructure, wire up anything the
	// pipeline cannot know about
	open fun onAssemble() {

	}

	/** Writes this Machine's own mutable state into [tag]. Base state rides MachineSerializer's header. */
	// only write what cannot be re-derived: progress counters, door extension, port bindings. never
	// world blocks — the world is the source of truth and is already saved by the server.
	open fun saveState(tag: CompoundTag) {

	}

	/** Reads back whatever [saveState] wrote. [tag] is empty for a machine saved before this type had state. */
	open fun loadState(tag: CompoundTag) {

	}

	/** Called after the Machine has been moved or rotated by the ship carrying it. */
	// recommended use: fix up anything caching an absolute position that the base cannot know about
	// (display entities, spawned particles' anchors). blockSet offsets need no fixup — they resolve
	// through localToWorld() against the already-updated origin/rotation.
	open fun onRelocate() {

	}

	/**
	 * Registers a freshly placed Machine, freezing the variants that fill its structure.
	 * Returns null when the structure does not fit at [origin], or when a port cell is already claimed.
	 */
	fun assemble(level: ServerLevel, origin: Vec3i, rotation: Rotation = Rotation.NONE): Machine? {

		val set = blockSet
		if (set != null) {

			val resolved = resolveStructure(set, level, origin, rotation) ?: return null

			// structure cells may be shared between machines, port cells may not
			val claimedPorts = portCellsOf(resolved, origin, rotation)
			for (other in activeMachines.values) {
				if (other.level != level) continue
				if (other.portWorldCells().any { it in claimedPorts }) return null
			}

			resolvedStructure.putAll(resolved)

		}

		bind(UUID.randomUUID(), level, origin, rotation)
		activate()

		markDirty() // never been written to the database, so the save loop has to pick it up

		return this

	}

	/** Restores a Machine from the database, rebinding the frozen structure it was assembled with. */
	fun load(uuid: UUID, level: ServerLevel, origin: Vec3i, rotation: Rotation, tag: CompoundTag): Machine {

		// bind before loadFrom so a subclass restoring world-facing state can already resolve
		// level/origin/rotation, and activate after it so onAssemble() sees a fully restored machine.
		bind(uuid, level, origin, rotation)
		loadFrom(tag)
		activate()

		return this

	}

	/** Full save payload: the frozen structure, buffer contents, then the type's own [saveState]. */
	// both blocks are variable-length — a buffer encodes whichever resource it happens to hold — which
	// is exactly why they live in the tag instead of MachineSerializer's fixed header.
	internal fun saveTo(tag: CompoundTag) {

		val structure = ListTag()
		for ((offset, matcher) in resolvedStructure) {

			val entry = CompoundTag()
			entry.putLong("cell", BlockPos.asLong(offset.x, offset.y, offset.z))
			entry.putString("matcher", matcher.key)

			structure.add(entry)

		}
		tag.put("structure", structure)

		val storedBuffers = ListTag()
		for (buffer in buffers.values) {

			val storedContents = ListTag()
			for ((held, units) in buffer.contents()) {

				val slot = CompoundTag()

				// an item variant carries its own data components; every other resource is a registry
				// singleton and only needs its key
				if (held is ItemKey) slot.putByteArray("item", held.toBytes())
				else slot.putString("resource", held.namespacedKey.key)

				slot.putLong("amount", units)
				storedContents.add(slot)

			}

			val entry = CompoundTag()
			entry.putString("key", buffer.key)
			entry.put("contents", storedContents)

			storedBuffers.add(entry)

		}
		tag.put("buffers", storedBuffers)

		saveState(tag)

	}

	/** Counterpart to [saveTo]. Runs after bind(), before registration. */
	private fun loadFrom(tag: CompoundTag) {

		// the stored matcher key is looked back up in the type's own blockSet, so a machine whose type
		// definition changed under it simply loses the cells that no longer exist.
		val set = blockSet
		if (set != null) {

			for (entry in tag.getListOrEmpty("structure").filterIsInstance<CompoundTag>()) {

				val cell = BlockPos.of(entry.getLongOr("cell", 0L))
				val offset = Vec3i(cell.x, cell.y, cell.z)

				val matcher = set.blockMap[offset]?.firstOrNull { it.key == entry.getStringOr("matcher", "") }
					?: continue

				resolvedStructure[offset] = matcher

			}

		}

		// buffers are declared by the type in onAssemble(), which has not run yet — stash and apply after
		restoredBuffers = tag.getListOrEmpty("buffers")
			.filterIsInstance<CompoundTag>()
			.associate { entry ->

				val contents = mutableMapOf<AnionResource, Long>()

				for (slot in entry.getListOrEmpty("contents").filterIsInstance<CompoundTag>()) {

					val itemBytes = slot.getByteArray("item").orElse(null)
					val held: AnionResource? =
						if (itemBytes != null) ItemKey.fromBytes(itemBytes)
						else AnionRegistries.resourceOf(AnionRegistryKey(slot.getStringOr("resource", "")))

					if (held == null) continue // resource was unregistered while this machine sat on disk

					contents[held] = slot.getLongOr("amount", 0L)

				}

				entry.getStringOr("key", "") to contents.toMap()

			}

		loadState(tag)

	}

	/** Tears down a Machine: hook first, then deregister so late-firing tasks still see it as active. */
	fun disassemble() {

		onDisassemble()

		spillBuffers()

		starship?.machines?.detach(this)
		MachineIndex.unregister(this)
		activeMachines.remove(uuid)
		AnionPersistence.deleteMachine(uuid, level) // destroyed, not unloaded — the save goes with it

	}

	/** identity assignment, shared by assemble() and load(). */
	private fun bind(uuid: UUID, level: ServerLevel, origin: Vec3i, rotation: Rotation) {

		this.uuid = uuid
		this.level = level
		this.origin = origin
		this.rotation = rotation

	}

	/** registration tail, shared by assemble() and load(). */
	// runs after loadFrom(), so resolvedStructure is populated by the time cells are indexed
	private fun activate() {

		recomputeOccupiedChunks()

		activeMachines[uuid] = this
		Starship.starshipAt(level, origin)?.machines?.attach(this) // claimed if the core sits on a ship
		MachineIndex.register(this) // no-op while carried — see MachineIndex

		intact = isIntact()

		onAssemble()

		applyRestoredBuffers()

		// claimed after the restore, so reading a machine back off disk does not mark it dirty. from
		// here on any change to a buffer marks the machine, which is the only reason it ever gets saved
		for (buffer in buffers.values) buffer.owner = this

	}

	/** Pushes saved buffer contents into the buffers the type declared in [onAssemble]. */
	private fun applyRestoredBuffers() {

		if (restoredBuffers.isEmpty()) return

		for ((bufferKey, contents) in restoredBuffers) buffers[bufferKey]?.restore(contents)

		restoredBuffers = emptyMap()

	}

	/** Moves this Machine to [newOrigin] and composes [addedRotation] onto its own. Ship-driven only. */
	// base owns this the same way it owns assemble(): it is the single writer of origin/rotation after
	// assembly, so no subclass can move itself out from under the structure check.
	fun relocate(newOrigin: Vec3i, addedRotation: Rotation) {

		val grounded = starship == null // a carried machine is not in the index, so it needs no reindexing
		if (grounded) MachineIndex.unregister(this)

		origin = newOrigin
		rotation += addedRotation

		recomputeOccupiedChunks()
		if (grounded) MachineIndex.register(this)

		onRelocate()
		markDirty()

	}

	/** Ticker entry point, called every game tick. */
	fun runTick() {

		if (starship?.moving == true) return // carrier is mid-rewrite, every world read would be torn

		if (intact) tick() // if it's intact instantly run tick()

	}

	/** Ticker entry point, called every second. */
	// structure is NOT re-checked here — revalidate() runs off block changes and carrier moves instead.
	fun runSlowTick() {

		if (starship?.moving == true) return

		if (intact) slowTick()

	}

	/** Re-runs the structure check. Tears the machine down once more than half its cells are wrong. */
	// called when a block inside this machine changed, and once a carrier move settles.
	fun revalidate() {

		if (!chunksLoaded()) return // a torn read would look like a demolished machine

		val result = structureResult()
		intact = result.intact

		if (!result.intact && result.brokenOffsets.size > resolvedStructure.size * DISASSEMBLY_THRESHOLD) {
			disassemble()
			return
		}

		onStructureChanged(result)

	}

	/** [offset] relative to origin, rotation-applied -> absolute world position. */
	fun localToWorld(offset: Vec3i): Vec3i = origin + offset.rotate(rotation)

	/** World read at [offset], resolved through [localToWorld]. */
	protected fun blockDataAt(offset: Vec3i): BlockData {

		val pos = localToWorld(offset)

		return level.world.getBlockAt(pos.x, pos.y, pos.z).blockData

	}

	/** World write at [offset], resolved through [localToWorld]. Committed sync. */
	protected fun setBlockAt(offset: Vec3i, blockData: BlockData) {

		Tasks.runSync {
			val pos = localToWorld(offset)
			level.world.getBlockAt(pos.x, pos.y, pos.z).blockData = blockData
		}

	}

	/** Marks the machine for the next save. Structure, origin or rotation changed — not "it ticked". */
	protected fun markDirty() {
		dirty = true
	}

	/** Every world cell this machine occupies. */
	internal fun worldCells(): Set<Vec3i> = resolvedStructure.keys.mapTo(mutableSetOf()) { localToWorld(it) }

	/** Moves this Machine into [newLevel]. Ship-driven only, and only ever alongside a [relocate]. */
	internal fun reassignLevel(newLevel: ServerLevel) {

		val grounded = starship == null
		if (grounded) MachineIndex.unregister(this)

		level = newLevel

		if (grounded) MachineIndex.register(this)

		markDirty()

	}

	/** Every world cell this machine's ports occupy. Exclusive — no two machines may share one. */
	internal fun portWorldCells(): Set<Vec3i> =
		resolvedStructure
			.filterValues { MachinePort.kindOf(it) != null }
			.keys
			.mapTo(mutableSetOf()) { localToWorld(it) }

	/** Every world cell this machine's ports of [kind] occupy. */
	fun portWorldCells(kind: MachinePort.Kind): Set<Vec3i> =
		resolvedStructure
			.filterValues { MachinePort.kindOf(it) == kind }
			.keys
			.mapTo(mutableSetOf()) { localToWorld(it) }

	/** The strongest redstone signal reaching any of this machine's [kind] ports. */
	fun portSignal(kind: MachinePort.Kind): Int =
		portWorldCells(kind).maxOfOrNull { level.world.getBlockAt(it.x, it.y, it.z).blockPower } ?: 0

	/** Empties every buffer and forgets them. Disassembly only — the machine is going away. */
	private fun spillBuffers() {

		for (buffer in buffers.values) spill(buffer)

		buffers.clear()

	}

	/**
	 * Empties [buffer], dropping whatever it held at [dropLocation], the core block by default. Gas,
	 * fluid and energy have nowhere to be put down, so they are voided by design.
	 *
	 * The machine keeps the buffer itself — this is the way out of loading the wrong resource into
	 * the wrong one, not a teardown. Callers acting on a player's behalf should check
	 * [MachineBuffer.spillable] first; this does not, so disassembly can empty everything.
	 */
	fun spill(buffer: MachineBuffer, dropLocation: Location = coreLocation()) {

		for ((held, units) in buffer.contents()) {

			val stack = when (held) {
				is ItemKey -> held.stack
				is AnionItem -> held.asItemStack()
				else -> continue
			}

			val stackSize = stack.maxStackSize.coerceAtLeast(1).toLong()
			var remaining = units

			while (remaining > 0L) {

				val quantity = minOf(remaining, stackSize).toInt()
				level.world.dropItem(dropLocation, stack.asQuantity(quantity))
				remaining -= quantity

			}

		}

		buffer.clear()
		markDirty()

	}

	/** Centre of the core block, where anything with nowhere better to go ends up. */
	fun coreLocation(): Location = Location(level.world, origin.x + 0.5, origin.y + 0.5, origin.z + 0.5)

	private fun portCellsOf(

		resolved: Map<Vec3i, BlockMatcher>,
		origin: Vec3i,
		rotation: Rotation,

	): Set<Vec3i> =
		resolved
			.filterValues { MachinePort.kindOf(it) != null }
			.keys
			.mapTo(mutableSetOf()) { origin + it.rotate(rotation) }

	private fun recomputeOccupiedChunks() {

		occupiedChunks = resolvedStructure.keys.mapTo(mutableSetOf()) { offset ->
			val pos = localToWorld(offset)
			((pos.x shr 4).toLong() shl 32) or ((pos.z shr 4).toLong() and 0xFFFFFFFFL)
		}

	}

	private fun chunksLoaded(): Boolean = occupiedChunks.all { packed ->
		level.world.isChunkLoaded((packed shr 32).toInt(), packed.toInt())
	}

}

/**
 * Which grounded machines occupy which world cells, and which of them are owed a structure re-check.
 *
 * Carried machines are deliberately absent: their cells move every tick, and their carrier already
 * calls [StarshipMachines.revalidate] once a move settles.
 */
object MachineIndex {

	/** machine uuids per world cell. machines may share structure cells, so this is a multimap. */
	// keyed on position alone — a cross-world collision is filtered out on read, which is cheaper than
	// carrying a level in every key
	private val cells: MutableMap<Vec3i, MutableSet<UUID>> = ConcurrentHashMap()

	/** machines whose structure changed since the last drain. */
	private val pending: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

	/** Indexes every cell [machine] occupies. No-op while the machine is carried by a ship. */
	fun register(machine: Machine) {

		if (machine.starship != null) return

		for (cell in machine.worldCells()) {
			cells.computeIfAbsent(cell) { ConcurrentHashMap.newKeySet() }.add(machine.uuid)
		}

	}

	/** Drops every cell [machine] occupies. */
	fun unregister(machine: Machine) {

		for (cell in machine.worldCells()) {
			val occupants = cells[cell] ?: continue
			occupants.remove(machine.uuid)
			if (occupants.isEmpty()) cells.remove(cell)
		}

		pending.remove(machine.uuid)

	}

	/** Every loaded machine with a cell at [vec] in [level]. */
	fun machinesAt(level: ServerLevel, vec: Vec3i): List<Machine> {

		val occupants = cells[vec] ?: return emptyList()

		return occupants.mapNotNull { uuid ->
			Machine.activeMachines[uuid]?.takeIf { it.level == level }
		}

	}

	/** Queues every machine holding [vec] for a structure re-check on the next slow tick. */
	fun markChanged(level: ServerLevel, vec: Vec3i) {

		for (machine in machinesAt(level, vec)) pending.add(machine.uuid)

	}

	/** Queues [machine] directly. For carried machines, which are not in the cell index. */
	// block events fire while the block is still in the world, so a re-check has to wait for the drain
	// rather than run inline off the event.
	fun markChanged(machine: Machine) {

		pending.add(machine.uuid)

	}

	/** Re-checks every queued machine and clears the queue. */
	// TODO: nothing sweeps machines whose blocks changed without firing an event (/setblock, other
	//       plugins). a slow round-robin re-check across activeMachines would close that gap.
	fun drainPending() {

		if (pending.isEmpty()) return

		val queued = pending.toList()
		pending.clear()

		for (uuid in queued) Machine.activeMachines[uuid]?.revalidate()

	}

}
