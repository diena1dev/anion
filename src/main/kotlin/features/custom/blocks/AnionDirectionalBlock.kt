package dev.diena.anion.features.custom.blocks

import dev.diena.anion.Anion
import dev.diena.anion.extensions.rotated
import net.kyori.adventure.text.Component
import net.minecraft.world.level.block.Rotation
import org.bukkit.Instrument
import org.bukkit.NamespacedKey
import org.bukkit.SoundGroup
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack

/**
 * An [AnionBlock] that occupies one note per facing instead of one note total. Every one of those notes
 * resolves back to this same instance, so a BlockSet that accepts this block accepts it facing any way,
 * and only [faceOf] cares which way a placed one points.
 *
 * A facing, not an axis: this block has a front. Where [AnionPillarBlock] says "runs along Y", this says
 * "points north" — the two are not interchangeable, and a block with a face on it needs all six (or, for
 * something wall-mounted, all four horizontal) notes rather than three.
 *
 * The model is authored facing north, matching vanilla's own convention for directional blocks;
 * [modelRotation] is what the resource pack datagen spins it by, so a facing costs a note and nothing
 * else — no extra model, no extra texture.
 */
open class AnionDirectionalBlock(

	displayName: String,
	instrument: Instrument,
	/** note per facing. order matters only in that the first entry is the default. */
	val notesByFace: Map<BlockFace, Int>,
	stacksTo: Int = 64,
	styledDisplayName: Component = Component.text(displayName),
	namespacedKey: NamespacedKey = NamespacedKey(Anion.NAMESPACE, displayName.replace(" ", "_").lowercase()),
	drops: ItemStack? = null,
	soundGroup: SoundGroup? = null,

	placeHandler: ((block: Block, player: Player?) -> Unit)? = null,
	breakHandler: ((block: Block, player: Player?) -> Unit)? = null,
	interactHandler: ((event: PlayerInteractEvent) -> Boolean)? = null,
	neighborChangeHandler: ((block: Block) -> Unit)? = null,

) : AnionBlock(

	displayName,
	instrument,
	// the note the item form carries, and what a placement starts as before it is turned to face
	notesByFace.values.first(),
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

		if (notesByFace.isEmpty()) throw IllegalStateException("$namespacedKey has no facings")

		for ((face, faceNote) in notesByFace) {
			if (faceNote !in 0..24) throw IllegalStateException("note must be 0–24, got $faceNote for $namespacedKey facing $face")
		}

		val duplicates = notesByFace.values.groupingBy { it }.eachCount().filterValues { it > 1 }
		if (duplicates.isNotEmpty()) throw IllegalStateException("$namespacedKey reuses notes ${duplicates.keys} across facings")

	}

	/** the ways this block can be pointed */
	val faces: Set<BlockFace> get() = notesByFace.keys

	/** how a freshly placed one points before anything turns it */
	val defaultFace: BlockFace get() = notesByFace.keys.first()

	/** Which way a placed one with [note] points, or null if that note is not one of ours. */
	fun faceOf(note: Int): BlockFace? = notesByFace.entries.firstOrNull { it.value == note }?.key

	/** The note encoding [face], or null if this block cannot point that way. */
	fun noteFor(face: BlockFace): Int? = notesByFace[face]

	/**
	 * The way a placement points, given the face that was built against and who built it.
	 *
	 * The front comes out of the face you clicked, so a block put on a wall faces into the room. A face
	 * this block cannot take — the floor, for a wall-mounted one — falls back to pointing at the player,
	 * which is what they were looking at it from.
	 */
	open fun faceFor(clickedFace: BlockFace?, player: Player?): BlockFace {

		if (clickedFace != null && clickedFace in faces) return clickedFace

		val towardsPlayer = player?.facing?.oppositeFace
		if (towardsPlayer != null && towardsPlayer in faces) return towardsPlayer

		return defaultFace

	}

	/** Turns the facing the note encodes, so a placed one still points the right way after a ship turns. */
	override fun noteAfterRotation(note: Int, rotation: Rotation): Int {

		val face = faceOf(note) ?: return note

		return noteFor(face.rotated(rotation)) ?: note

	}

	override fun noteOnPlacement(block: Block, clickedFace: BlockFace?, player: Player?): Int? =
		noteFor(faceFor(clickedFace, player))

	override fun stateVariants(): Map<Int, Pair<Int, Int>> =
		notesByFace.entries.associate { (face, note) -> note to modelRotation(face) }

	/**
	 * Blockstate model rotation for [face], as `x` to `y` degrees. Assumes the model is drawn facing
	 * north, matching vanilla's own convention for directional blocks.
	 */
	open fun modelRotation(face: BlockFace): Pair<Int, Int> = when (face) {

		BlockFace.NORTH -> 0 to 0
		BlockFace.EAST -> 0 to 90
		BlockFace.SOUTH -> 0 to 180
		BlockFace.WEST -> 0 to 270

		// a model drawn facing north is tipped onto its back to point up, and onto its face to point down
		BlockFace.UP -> 270 to 0
		BlockFace.DOWN -> 90 to 0

		else -> 0 to 0

	}

}
