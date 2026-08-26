package dev.diena.anion.features.custom.blocks

import dev.diena.anion.Anion
import dev.diena.anion.extensions.axis
import dev.diena.anion.extensions.rotated
import net.kyori.adventure.text.Component
import net.minecraft.world.level.block.Rotation
import org.bukkit.Axis
import org.bukkit.Instrument
import org.bukkit.NamespacedKey
import org.bukkit.SoundGroup
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack

/**
 * An [AnionBlock] that occupies one note per axis instead of one note total. Every one of those notes
 * resolves back to this same instance, so a BlockSet that accepts this block accepts it in any
 * orientation, and only [axisOf] cares which way a placed one runs.
 *
 * An axis, not a facing: a pillar has two ends and no front, so anything running through it runs both
 * ways. Three notes cover every orientation where six would only be able to say the same thing twice.
 *
 * The model is authored standing on Y, matching vanilla's own pillars; [modelRotation] is what the
 * resource pack datagen spins it by, so an axis costs a note and nothing else — no extra model, no
 * extra texture.
 */
open class AnionPillarBlock(

	displayName: String,
	instrument: Instrument,
	/** note per axis. order matters only in that the first entry is the default. */
	val notesByAxis: Map<Axis, Int>,
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
	// the note the item form carries, and what a placement starts as before it is turned to lie
	notesByAxis.values.first(),
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
		if (notesByAxis.isEmpty()) throw IllegalStateException("$namespacedKey has no axes")

		for ((axis, axisNote) in notesByAxis) {
			if (axisNote !in 0..24) throw IllegalStateException("note must be 0–24, got $axisNote for $namespacedKey axis $axis")
		}

		val duplicates = notesByAxis.values.groupingBy { it }.eachCount().filterValues { it > 1 }
		if (duplicates.isNotEmpty()) throw IllegalStateException("$namespacedKey reuses notes ${duplicates.keys} across axes")
	}

	/** the axes this block can be placed along */
	val axes: Set<Axis> get() = notesByAxis.keys

	/** how a freshly placed one lies before anything turns it */
	val defaultAxis: Axis get() = notesByAxis.keys.first()

	/** Which way a placed one with [note] runs, or null if that note is not one of ours. */
	fun axisOf(note: Int): Axis? = notesByAxis.entries.firstOrNull { it.value == note }?.key

	/** The note encoding [axis], or null if this block cannot lie that way. */
	fun noteFor(axis: Axis): Int? = notesByAxis[axis]

	/**
	 * The axis a placement lands on, given the face of the block that was clicked. Vanilla's rule for
	 * logs: the pillar runs out of the face you clicked.
	 */
	open fun axisFor(clickedFace: BlockFace?): Axis =
		clickedFace?.axis?.takeIf { it in axes } ?: defaultAxis

	/** Turns the axis the note encodes, so a pillar lies the right way after a starship rotation. */
	override fun noteAfterRotation(note: Int, rotation: Rotation): Int {

		val axis = axisOf(note) ?: return note

		return noteFor(axis.rotated(rotation)) ?: note

	}

	/** A fresh pillar lies along the axis its placement implies. */
	override fun noteOnPlacement(block: Block, clickedFace: BlockFace?, player: Player?): Int? =
		noteFor(axisFor(clickedFace))

	override fun stateVariants(): Map<Int, Pair<Int, Int>> =
		notesByAxis.entries.associate { (axis, note) -> note to modelRotation(axis) }

	/**
	 * Blockstate model rotation for [axis], as `x` to `y` degrees. Assumes the model stands on Y,
	 * matching vanilla's own convention for pillars.
	 */
	open fun modelRotation(axis: Axis): Pair<Int, Int> = when (axis) {

		Axis.Y -> 0 to 0
		Axis.Z -> 90 to 0
		Axis.X -> 90 to 90

	}

}
