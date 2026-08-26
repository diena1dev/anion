package dev.diena.anion.features.machine.component

import dev.diena.anion.Anion
import dev.diena.anion.extensions.anionBlock
import dev.diena.anion.extensions.anionFace
import dev.diena.anion.extensions.spawn
import dev.diena.anion.features.custom.blocks.AnionDisplayBlock
import dev.diena.anion.features.machine.Machine
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.minecraft.core.Vec3i
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.BlockFace
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.TextDisplay
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Transformation
import org.joml.Vector3f
import java.util.UUID

/**
 * A rectangle of [AnionDisplayBlock] cells on one face of a machine, and the text drawn over it.
 *
 * The blocks are the surface and nothing else; this owns what is written on them. A screen is one
 * [TextDisplay] entity floating just off the block face, not one per block — text that spans blocks has
 * to be one piece of text, or every seam becomes a place a word can be cut in half.
 *
 * Screens are resolved from the world, not declared in a [dev.diena.anion.features.machine.BlockSet]: a
 * machine can be built with a two-by-one panel or a six-by-three one and the same code drives both.
 */
class MachineDisplay private constructor(

	val machine: Machine,
	/** which way the screen is read from */
	val facing: BlockFace,
	/** world cells this screen covers */
	val cells: Set<Vec3i>,
	/** blocks across, as the reader sees it */
	val width: Int,
	/** blocks tall */
	val height: Int,
	/** lowest corner of the rectangle, world space */
	private val corner: Vec3i,

) {

	companion object {

		/** marks a text display as belonging to a machine, so orphans can be found and swept */
		private val OWNER_KEY = NamespacedKey(Anion.NAMESPACE, "display_owner")

		/** vanilla renders display text at one pixel per fortieth of a block */
		private const val PIXELS_PER_BLOCK = 40.0

		/** a line of text is nine pixels of glyph and one of gap */
		private const val LINE_PIXELS = 10.0

		/** widest a default-font glyph gets, including its trailing pixel */
		private const val GLYPH_PIXELS = 6.0

		/** width of a space in the default font, including its trailing pixel */
		private const val SPACE_PIXELS = 4.0

		/** longest spacer worth building, so a silly scale cannot ask for a megabyte of whitespace */
		private const val MAX_SPACER = 512

		/** how far off the block face the backdrop floats. enough to clear z-fighting, not enough to see. */
		private const val SURFACE_OFFSET = 0.02

		/** how thick the backdrop is. it is a panel, not a slab. */
		private const val BACKDROP_DEPTH = 0.01f

		/** how far the text floats in front of the backdrop */
		private const val TEXT_OFFSET = 0.01

		/**
		 * Where a text display's block of text sits relative to its own anchor, as a fraction of that
		 * block's height. `0.0` grows upward out of the anchor, `0.5` centres on it.
		 */
		// this is the one number to change if the text sits a whole panel out of place vertically. the
		// symptom that set it to 0 was text rendering above a screen it was told to sit at the top of.
		private const val TEXT_ANCHOR_RISE = 0.0

		/** what the backdrop is made of. any full solid block reads as a screen; black reads as a dark one. */
		private val BACKDROP_MATERIAL = Material.BLACK_CONCRETE

		/** rows a screen is sized for until something says otherwise */
		// eight on a two-block-tall panel puts the text at roughly vanilla size
		const val DEFAULT_ROWS = 8

		/** cells one screen may grow to before the search gives up on it */
		private const val MAX_CELLS = 256

		/**
		 * Every screen on [machine]: display cells grouped by the face they point and the plane they sit
		 * in, keeping only the groups that fill a solid rectangle.
		 *
		 * Main thread only — this reads the world.
		 */
		fun resolve(machine: Machine): List<MachineDisplay> {

			val seeds = mutableMapOf<Pair<BlockFace, Int>, MutableSet<Vec3i>>()

			for (offset in machine.resolvedStructure.keys) {

				val cell = machine.localToWorld(offset)
				val block = machine.level.world.getBlockAt(cell.x, cell.y, cell.z)

				if (block.anionBlock !is AnionDisplayBlock) continue

				// a display that was turned by a starship carries its new facing in the world already, so
				// grouping on what is actually there needs no rotation maths
				val face = block.anionFace ?: continue

				seeds.getOrPut(face to depthOf(cell, face)) { mutableSetOf() } += cell

			}

			return seeds.mapNotNull { (key, group) -> rectangle(machine, key.first, grow(machine, key.first, group)) }

		}

		/**
		 * Every display cell reachable from [seeds] by walking neighbours in the same plane that point the
		 * same way, whether or not they belong to the machine's declared structure.
		 *
		 * This is what makes a panel expandable: the [dev.diena.anion.features.machine.BlockSet] declares
		 * the smallest screen a machine will accept, and a player who wants a bigger one bricks more
		 * display blocks onto it.
		 */
		private fun grow(machine: Machine, facing: BlockFace, seeds: Set<Vec3i>): Set<Vec3i> {

			val world = machine.level.world

			val found = seeds.toMutableSet()
			val queue = ArrayDeque(seeds)

			while (queue.isNotEmpty()) {

				// a panel someone decided to make the size of a chunk is a mistake, not a screen
				if (found.size >= MAX_CELLS) break

				val cell = queue.removeFirst()

				for (neighbour in neighboursInPlane(cell, facing)) {

					if (neighbour in found) continue

					val block = world.getBlockAt(neighbour.x, neighbour.y, neighbour.z)

					if (block.anionBlock !is AnionDisplayBlock) continue
					if (block.anionFace != facing) continue // a panel turning a corner is two panels

					found += neighbour
					queue += neighbour

				}

			}

			return found

		}

		/** the four cells touching this one within the screen's own plane */
		private fun neighboursInPlane(cell: Vec3i, facing: BlockFace): List<Vec3i> {

			val across =
				if (facing == BlockFace.NORTH || facing == BlockFace.SOUTH) listOf(Vec3i(1, 0, 0), Vec3i(-1, 0, 0))
				else listOf(Vec3i(0, 0, 1), Vec3i(0, 0, -1))

			return (across + listOf(Vec3i(0, 1, 0), Vec3i(0, -1, 0)))
				.map { step -> Vec3i(cell.x + step.x, cell.y + step.y, cell.z + step.z) }

		}

		/** Removes every screen entity belonging to [machine], including ones this session did not spawn. */
		fun clear(machine: Machine) {

			val owner = machine.uuid.toString()

			// both halves of a screen carry the tag, so this sweeps backdrops and text alike
			for (entity in machine.level.world.getEntitiesByClass(Display::class.java)) {

				if (entity.persistentDataContainer.get(OWNER_KEY, PersistentDataType.STRING) != owner) continue

				entity.remove()

			}

		}

		/** The screen built from [group], or null when those cells are not a filled rectangle. */
		private fun rectangle(machine: Machine, facing: BlockFace, group: Set<Vec3i>): MachineDisplay? {

			val acrossAxis = acrossAxisOf(facing)

			val acrossValues = group.map { acrossAxis(it) }
			val heightValues = group.map { it.y }

			val minAcross = acrossValues.min()
			val minHeight = heightValues.min()

			val width = acrossValues.max() - minAcross + 1
			val height = heightValues.max() - minHeight + 1

			// a filled rectangle is the only shape with exactly as many cells as its bounding box. an L
			// or a ring would still have a bounding box, and text drawn over one would hang in the air.
			if (width * height != group.size) return null

			val sample = group.first()
			val corner = when (facing) {

				BlockFace.NORTH, BlockFace.SOUTH -> Vec3i(minAcross, minHeight, sample.z)
				else -> Vec3i(sample.x, minHeight, minAcross)

			}

			return MachineDisplay(machine, facing, group, width, height, corner)

		}

		/** the plane a cell sits in, along the axis the facing runs */
		private fun depthOf(cell: Vec3i, facing: BlockFace): Int =
			if (facing == BlockFace.NORTH || facing == BlockFace.SOUTH) cell.z else cell.x

		/** reads the coordinate that runs across the screen, given which way it faces */
		private fun acrossAxisOf(facing: BlockFace): (Vec3i) -> Int =
			if (facing == BlockFace.NORTH || facing == BlockFace.SOUTH) { cell -> cell.x } else { cell -> cell.z }

		/** Yaw that aims a display along [facing], so its front is the side a reader stands on. */
		private fun yawFacing(facing: BlockFace): Float = when (facing) {

			BlockFace.SOUTH -> 0f
			BlockFace.WEST -> 90f
			BlockFace.NORTH -> 180f
			else -> 270f

		}

	}

	/** rows this screen is sized for. more rows means smaller letters. */
	var rows: Int = DEFAULT_ROWS; private set

	/** rows a caller may actually fill. the bottom one belongs to the [spacer]. */
	val usableRows: Int get() = (rows - 1).coerceAtLeast(1)

	/** what is currently written, one entry per line */
	var lines: List<Component> = emptyList(); private set

	/** the solid panel the text is drawn on. sized to the blocks and never to the text. */
	private var backdropUuid: UUID? = null

	/** the text itself, floating a hair in front of the backdrop */
	private var textUuid: UUID? = null

	/** how many blocks tall one line of text is at the current [rows] */
	private val blocksPerRow: Double get() = height.toDouble() / rows

	/** scale that makes [rows] lines exactly fill the screen's height */
	private val scale: Float get() = (blocksPerRow * PIXELS_PER_BLOCK / LINE_PIXELS).toFloat()

	/**
	 * Roughly how many default-font characters fit on one line at the current scale.
	 *
	 * Approximate on purpose: the font is proportional, so a line of `l` fits far more than a line of
	 * `W`. Callers that need text to stop at the edge should let it wrap rather than trusting this.
	 */
	val columns: Int get() = (width * PIXELS_PER_BLOCK / (GLYPH_PIXELS * scale)).toInt().coerceAtLeast(1)

	/** Resizes the text so [rows] lines fill the screen. Fewer rows, bigger letters. */
	fun scaleToRows(rows: Int) {

		this.rows = rows.coerceAtLeast(1)
		apply()

	}

	/** Replaces everything on the screen. */
	fun write(lines: List<Component>) {

		this.lines = lines
		apply()

	}

	/** Replaces one line, padding the screen out with blanks if it does not reach that far yet. */
	fun line(index: Int, text: Component) {

		if (index < 0) return

		val updated = lines.toMutableList()
		while (updated.size <= index) updated += Component.empty()

		updated[index] = text
		write(updated)

	}

	/** The line at [index], or an empty component when nothing has been written there. */
	fun line(index: Int): Component = lines.getOrElse(index) { Component.empty() }

	fun clear() = write(emptyList())

	/** Drops this screen's entities. The blocks stay; only what was drawn on them goes. */
	fun remove() {

		backdrop()?.remove()
		text()?.remove()

		backdropUuid = null
		textUuid = null

	}

	/** Draws the current text, spawning the entities if they are not there yet. Main thread only. */
	fun apply() {

		// a broken machine draws nothing: its cells may not be display blocks any more
		if (!machine.intact) {

			remove()
			return

		}

		applyBackdrop(backdrop() ?: spawnBackdrop())
		applyText(text() ?: spawnText())

	}

	/**
	 * Sizes the backdrop to the panel and nothing else.
	 *
	 * The anchor is the panel's bottom-left corner as the reader sees it, and a block display grows out
	 * of its own origin along local +X (right), +Y (up) and +Z (towards the reader). So a panel three by
	 * two is a scale of three by two and no translation at all.
	 */
	// TODO: developer wants this swapped off a BlockDisplay. It is one because a text display's
	//       background is sized by the text inside it, which is the one thing that must not decide how
	//       big the screen is — a fixed-size text background needs the empty-text quad measured first.
	private fun applyBackdrop(display: BlockDisplay) {

		display.transformation = Transformation(
			Vector3f(0f, 0f, -0.02f),
			display.transformation.leftRotation,
			Vector3f(width.toFloat(), height.toFloat(), BACKDROP_DEPTH),
			display.transformation.rightRotation,
		)

		display.teleport(anchor(SURFACE_OFFSET))

	}

	private fun applyText(display: TextDisplay) {

		display.text(Component.join(JoinConfiguration.newlines(), drawn()))

		// wrap at the physical edge of the panel. this is the whole reason a screen beats a sign: text
		// too long for the space folds onto the next line instead of being cut off.
		display.lineWidth = (width * PIXELS_PER_BLOCK / scale).toInt().coerceAtLeast(1)

		// the block of text is always exactly the panel, so it needs no vertical offset at all: it grows
		// upward out of an anchor that is already the panel floor. horizontally it is centred on the
		// anchor, so half the width brings its left edge onto the left corner.
		display.transformation = Transformation(
			Vector3f((width / 2.0).toFloat(), (height * TEXT_ANCHOR_RISE).toFloat(), 0f),
			display.transformation.leftRotation,
			Vector3f(scale, scale, scale),
			display.transformation.rightRotation,
		)

		display.teleport(anchor(SURFACE_OFFSET + BACKDROP_DEPTH + TEXT_OFFSET))

	}

	/**
	 * What actually goes on the screen: the written lines, blanks for the rows nobody wrote, and the
	 * [spacer] holding the block out to full width.
	 *
	 * Always exactly [rows] lines. A text display is anchored at the bottom of its own text and sized by
	 * what is in it, so a block whose height moved with the line count would drift up the wall every time
	 * something was written — and any hand-tuned offset would only be right for one line count.
	 */
	private fun drawn(): List<Component> {

		val padded = lines.take(usableRows).toMutableList()
		while (padded.size < usableRows) padded += Component.empty()

		return padded + Component.text(spacer())

	}

	/**
	 * A line of spaces as wide as the panel, drawn as the bottom row.
	 *
	 * A text display's block is only as wide as its widest line, and it is that block which gets centred
	 * on the anchor horizontally. Without something holding it out to full width, `["hello world"]` sits
	 * in the middle of the panel however hard the alignment is set to left.
	 */
	// TODO: a spacer line is a workaround, and it costs the bottom row of the screen. The real fix is a
	//       default-font glyph width table, which would let the text be offset by its own measured width.
	private fun spacer(): String {

		val panelPixels = width * PIXELS_PER_BLOCK / scale

		return " ".repeat((panelPixels / SPACE_PIXELS).toInt().coerceIn(1, MAX_SPACER))

	}

	private fun backdrop(): BlockDisplay? =
		backdropUuid?.let { machine.level.world.getEntity(it) as? BlockDisplay }

	private fun text(): TextDisplay? =
		textUuid?.let { machine.level.world.getEntity(it) as? TextDisplay }

	private fun spawnBackdrop(): BlockDisplay {

		val display = machine.level.world.spawn<BlockDisplay>(anchor(SURFACE_OFFSET)) { display ->

			display.block = BACKDROP_MATERIAL.createBlockData()
			display.billboard = Display.Billboard.FIXED
			display.brightness = Display.Brightness(15, 15) // a screen is lit, not lit by the room

			tag(display)

		}

		backdropUuid = display.uniqueId

		return display

	}

	private fun spawnText(): TextDisplay {

		val display = machine.level.world.spawn<TextDisplay>(anchor(SURFACE_OFFSET + BACKDROP_DEPTH + TEXT_OFFSET)) { display ->

			display.billboard = Display.Billboard.FIXED
			display.alignment = TextDisplay.TextAlignment.LEFT
			display.isShadowed = false
			display.isSeeThrough = false
			display.brightness = Display.Brightness(15, 15)

			// the backdrop is the background. this one carries glyphs and nothing behind them.
			display.isDefaultBackground = false
			display.backgroundColor = Color.fromARGB(0, 0, 0, 0)

			tag(display)

		}

		textUuid = display.uniqueId

		return display

	}

	/** marks an entity as this machine's, so a sweep can find it again after a restart */
	private fun tag(display: Display) =
		display.persistentDataContainer.set(OWNER_KEY, PersistentDataType.STRING, machine.uuid.toString())

	/**
	 * The panel's bottom-left corner as the reader sees it, pushed [outward] blocks clear of the blocks.
	 *
	 * Everything is placed from here and moved by its own transformation, which is what makes the
	 * backdrop a bare scale with no offset at all. Local axes on a display run +X right, +Y up and +Z
	 * out of the screen, so "bottom-left" is the origin those three grow away from.
	 */
	private fun anchor(outward: Double): Location {

		val world = machine.level.world
		val yaw = yawFacing(facing)

		val flat = facing == BlockFace.NORTH || facing == BlockFace.SOUTH

		val acrossMin = (if (flat) corner.x else corner.z).toDouble()
		val plane = (if (flat) corner.z else corner.x).toDouble()

		// which end of the across axis is the reader's left depends on which side they stand on: a
		// north-facing panel is read from the north, so its left edge is the high end of x
		val left = when (facing) {

			BlockFace.NORTH, BlockFace.EAST -> acrossMin + width
			else -> acrossMin

		}

		// the readable face is the near side of the block for north and west, the far side for the others
		val face = when (facing) {

			BlockFace.NORTH, BlockFace.WEST -> plane - outward
			else -> plane + 1.0 + outward

		}

		val bottom = corner.y.toDouble()

		return if (flat) Location(world, left, bottom, face, yaw, 0f)
		else Location(world, face, bottom, left, yaw, 0f)

	}

}
