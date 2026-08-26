package dev.diena.anion.features.machine.machine_types.debug.scripting

import dev.diena.anion.features.custom.blocks.AnionBlocks
import dev.diena.anion.features.machine.BlockSet
import dev.diena.anion.features.machine.Machine
import dev.diena.anion.features.machine.component.MachineDisplay
import dev.diena.anion.features.scripting.DcProgrammable
import dev.diena.anion.features.scripting.DcType
import dev.diena.anion.features.scripting.DcValue
import net.kyori.adventure.text.Component
import net.minecraft.core.Vec3i

/**
 * Three display blocks wide and two tall, with a dataport behind them.
 *
 * The panel declared here is the *smallest* one this machine will accept. Brick more display blocks onto
 * it and the screen grows to fit, as long as what you end up with is still a rectangle.
 */
val DEBUG_DISPLAY_STRUCTURE = BlockSet.new("debug_display")

	.core('C', AnionBlocks.COPPER_MACHINE_DATAPORT)

	.assign('c', AnionBlocks.COPPER_MACHINE_CASING)
	.assign('D', AnionBlocks.COPPER_MACHINE_DISPLAY)

	// slices are Y layers; rows in a slice run along X and characters along Z
	.slice(
		"DC",
		"Dc",
		"Dc",
	)
	.slice(
		"Dc",
		"Dc",
		"Dc",
	)

	.build()

/** A screen a dcprgm can write to. What the display component is for, with nothing else attached. */
class DebugDisplayMachine : Machine("debug_display", DEBUG_DISPLAY_STRUCTURE), DcProgrammable {

	companion object {

		/** lines that can be addressed by name, as `line_0` through `line_7` */
		const val ADDRESSABLE_LINES = 8

		private const val LINE_PREFIX = "line_"

		/** rows a screen may be squeezed to before the letters stop being letters */
		private const val MAX_ROWS = 24

	}

	/** what is on the screen, top line first */
	private val lines: MutableList<Component> = mutableListOf()

	private var screens: List<MachineDisplay> = emptyList()

	/** cells the screens covered last time they were resolved, so a rebuild only happens on a change */
	private var knownCells: Set<Vec3i> = emptySet()

	private var rows: Int = MachineDisplay.DEFAULT_ROWS

	override val dataInputs: Map<String, DcType> = mapOf(

		"panels" to DcType.NUM,
		"rows" to DcType.NUM,
		"columns" to DcType.NUM,

	)

	override val dataFunctions: List<String> =
		listOf("write", "clear", "smaller", "bigger") + (0 until ADDRESSABLE_LINES).map { "$LINE_PREFIX$it" }

	override fun onAssemble() {

		super.onAssemble()
		rebuild()

	}

	override fun onDisassemble() {

		MachineDisplay.clear(this)

		screens = emptyList()
		knownCells = emptySet()

	}

	override fun tick() {

		// NO-OP: a screen only changes when something writes to it

	}

	// the panel is expandable, so the world has to be re-read for blocks bricked on since the last check
	override fun slowTick() = rebuild()

	override fun invoke(function: String, value: DcValue) {

		// a hold binding delivers false on release, and a screen has nothing to do with that
		if (!value.truthy) return

		when {

			function == "clear" -> {

				lines.clear()
				push()

			}

			// the value is the line, so this is where a dclang string actually lands on a wall
			function == "write" -> {

				lines += Component.text(value.toString())
				while (lines.size > usableRows()) lines.removeFirst() // scroll, like any other console

				push()

			}

			function == "smaller" -> scaleTo(rows + 1)
			function == "bigger" -> scaleTo(rows - 1)

			function.startsWith(LINE_PREFIX) -> {

				val index = function.removePrefix(LINE_PREFIX).toIntOrNull() ?: return

				while (lines.size <= index) lines += Component.empty()
				lines[index] = Component.text(value.toString())

				push()

			}

		}

	}

	override fun debugLines(): List<String> = listOf(

		"screens=${screens.size} rows=$rows usable=${usableRows()} lines=${lines.size}",
		*screens.map { "  ${it.width}x${it.height} facing ${it.facing} | ~${it.columns} columns" }.toTypedArray(),

	)

	/** Re-reads the panel out of the world, keeping the text that was on it. */
	private fun rebuild() {

		val resolved = MachineDisplay.resolve(this)
		val cells = resolved.flatMapTo(HashSet()) { it.cells }

		// nothing was built onto or broken off the panel, so the screens standing there are still right
		if (cells == knownCells && screens.size == resolved.size) return

		// the geometry moved, so the old entities are the wrong size in the wrong place
		MachineDisplay.clear(this)

		screens = resolved
		knownCells = cells

		push()

	}

	/** Lines that fit, once a screen has taken its own row back. */
	private fun usableRows(): Int = screens.minOfOrNull { it.usableRows } ?: rows

	private fun scaleTo(rows: Int) {

		this.rows = rows.coerceIn(2, MAX_ROWS) // one row belongs to the screen, so a single row is none

		push()

		while (lines.size > usableRows()) lines.removeFirst()

		push()

	}

	/** Puts the current text onto every screen this machine owns. */
	private fun push() {

		for (screen in screens) {

			screen.scaleToRows(rows)
			screen.write(lines)

		}

	}

}
