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
 * An [AnionBlock] that occupies one note per facing instead of one note total. Every one of those
 * notes resolves back to this same instance, so a BlockSet that accepts this block accepts it in any
 * rotation, and only [facingOf] cares which way a placed one points.
 *
 * The model is authored pointing north; [modelRotation] is what the resource pack datagen spins it by,
 * so a facing costs a note and nothing else — no extra model, no extra texture.
 */
open class AnionDirectionalBlock(

	displayName: String,
	instrument: Instrument,
	/** note per facing. order matters only in that the first entry is the default. */
	val notesByFacing: Map<BlockFace, Int>,
	stacksTo: Int = 64,
	styledDisplayName: Component = Component.text(displayName),
	namespacedKey: NamespacedKey = NamespacedKey(Anion.NAMESPACE, displayName.replace(" ", "_").lowercase()),
	drops: ItemStack? = null,
	soundGroup: SoundGroup? = null,

	placeHandler: ((block: Block, player: Player?) -> Unit)? = null,
	breakHandler: ((block: Block, player: Player?) -> Unit)? = null,
	interactHandler: ((event: PlayerInteractEvent) -> Unit)? = null,
	neighborChangeHandler: ((block: Block) -> Unit)? = null,

) : AnionBlock(
	displayName,
	instrument,
	// the note the item form carries, and what a placement starts as before it is turned to face
	notesByFacing.values.first(),
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
		if (notesByFacing.isEmpty()) throw IllegalStateException("$namespacedKey has no facings")

		for ((facing, facingNote) in notesByFacing) {
			if (facingNote !in 0..24) throw IllegalStateException("note must be 0–24, got $facingNote for $namespacedKey facing $facing")
		}

		val duplicates = notesByFacing.values.groupingBy { it }.eachCount().filterValues { it > 1 }
		if (duplicates.isNotEmpty()) throw IllegalStateException("$namespacedKey reuses notes ${duplicates.keys} across facings")
	}

	/** the facings this block can be placed in */
	val facings: Set<BlockFace> get() = notesByFacing.keys

	/** how a freshly placed one points before anything turns it */
	val defaultFacing: BlockFace get() = notesByFacing.keys.first()

	/** Which way a placed one with [note] points, or null if that note is not one of ours. */
	fun facingOf(note: Int): BlockFace? = notesByFacing.entries.firstOrNull { it.value == note }?.key

	/** The note encoding [facing], or null if this block cannot point that way. */
	fun noteFor(facing: BlockFace): Int? = notesByFacing[facing]

	/**
	 * Blockstate model rotation for [facing], as `x` to `y` degrees. Assumes the model is authored
	 * pointing north, matching vanilla's own convention for directional blocks.
	 */
	open fun modelRotation(facing: BlockFace): Pair<Int, Int> = when (facing) {

		BlockFace.NORTH -> 0 to 0
		BlockFace.EAST  -> 0 to 90
		BlockFace.SOUTH -> 0 to 180
		BlockFace.WEST  -> 0 to 270
		BlockFace.UP    -> 270 to 0
		BlockFace.DOWN  -> 90 to 0

		else -> throw IllegalStateException("non-cartesian facing $facing on $namespacedKey")

	}

}
