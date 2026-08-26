package dev.diena.anion.features.machine.machine_types.scripting.mainframe

import dev.diena.anion.features.machine.component.MachineSign
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minecraft.core.Vec3i
import org.bukkit.entity.Player

/**
 * The three signs across the front of a mainframe, and what is currently on them.
 *
 * State is per console rather than per player: the signs are one physical object, so two people stood at
 * the same mainframe are looking at the same page.
 */
class MainframeConsole private constructor(private val mainframe: MainframeMachine) {

	companion object {

		fun new(mainframe: MainframeMachine): MainframeConsole = MainframeConsole(mainframe)

		/** the console signs, local to the core. left and right are the reader's, not the machine's. */
		val SIGN_LEFT   = Vec3i(1, 1, -1)
		val SIGN_CENTER = Vec3i(0, 1, -1)
		val SIGN_RIGHT  = Vec3i(-1, 1, -1)

		/** the entry at the end of the machine list that walks back out of it */
		private const val BACK_ENTRY = " back"

		/** the mainframe's own file. it belongs to no machine, so it rides the list as its own entry */
		private const val GROUPS_ENTRY = "groups.dcprgm"

		/** characters that fit on one line of a sign */
		private const val LINE_WIDTH = 15

		/** what `>_ ` and `.sh` cost a name on a prompt line */
		private const val PROMPT_FRAME = 6

	}

	private enum class Page { MAIN, LIST, SELECTOR }

	private var page = Page.MAIN

	/** where the list page's selector is sat */
	private var cursor = 0

	/** the machine the selector page is showing */
	private var selected: String? = null

	/** the compute lines as last written, so the sign is only rewritten when a reading moves */
	private var lastStatus: List<String> = emptyList()

	/** Handles a click on the sign at [offset]. Anything that is not a console sign is ignored. */
	fun click(offset: Vec3i, player: Player) {

		when (page) {

			Page.MAIN -> if (offset == SIGN_LEFT) open(Page.LIST)

			Page.LIST -> when (offset) {

				SIGN_LEFT -> step(-1)
				SIGN_RIGHT -> step(1)
				SIGN_CENTER -> choose(player)

				else -> {}

			}

			Page.SELECTOR -> when (offset) {

				SIGN_CENTER -> open(Page.LIST)
				SIGN_LEFT -> selected?.let { mainframe.openRenameDialog(player, it) }
				SIGN_RIGHT -> selected?.let { mainframe.openEditorDialog(player, it) }

				else -> {}

			}

		}

	}

	/** Redraws all three signs for the page the console is on. */
	fun render() {

		when (page) {

			Page.MAIN -> {

				lastStatus = statusLines()

				val tripped = mainframe.budget.tripped
				val overCommitted = mainframe.store.totalCost > mainframe.budget.limit

				write(
					SIGN_CENTER,
					green(prompt("mainframe")),
					green("machines_${mainframe.onlineNames.size}"),
					if (tripped) red(lastStatus[0]) else green(lastStatus[0]),
					if (tripped || overCommitted) red(lastStatus[1]) else green(lastStatus[1]),
				)

				write(SIGN_LEFT, green(">_ machine_list"), blank(), blank(), blank())
				write(SIGN_RIGHT, blank(), blank(), blank(), blank())

			}

			Page.LIST -> {

				val entries = entries()

				// a machine unplugging under the cursor shortens the list out from under it
				cursor = cursor.coerceIn(0, entries.lastIndex)

				val entry = entries.getOrNull(cursor)

				write(SIGN_LEFT, green(">_ left"), blank(), blank(), blank())
				write(SIGN_RIGHT, green(">_ right"), blank(), blank(), blank())

				val position = value("[${cursor + 1}/${entries.size}]")

				when (entry) {

					BACK_ENTRY -> write(SIGN_CENTER, green(prompt("machine")), blank(), blank(), green("back"))
					GROUPS_ENTRY -> write(SIGN_CENTER, green(">_ groups.dcprgm"), position, blank(), green("edit"))

					else -> {

						val name = entry ?: ""

						write(
							SIGN_CENTER,
							green(prompt(name)),
							position,
							blank(),
							green("select"),
						)

					}

				}

			}

			Page.SELECTOR -> {

				val name = selected

				// unplugged while somebody was looking at it: there is nothing here to show anymore
				if (name == null || !mainframe.isOnline(name)) {

					selected = null
					open(Page.LIST)

					return

				}

				val program = mainframe.store.programOf(name)
				val hasFile = name in mainframe.store.fileNames

				write(SIGN_LEFT, green(">_ rename"), blank(), blank(), blank())
				write(SIGN_RIGHT, green(">_ edit"), blank(), blank(), blank())

				// a file with no compiled program behind it stopped compiling — say so rather than
				// showing a cost of nothing, which reads as a program that does nothing
				val fileLine = when {

					program != null -> green("main.dcprgm_${program.cost}")
					hasFile -> red("main.dcprgm ERR")
					else -> blank()

				}

				write(
					SIGN_CENTER,
					green(prompt(name)),
					green("ls"),
					fileLine,
					green("back"),
				)

			}

		}

	}

	/** Redraws the main page when a compute reading has moved. Cheap enough to call every tick. */
	fun refreshStatus() {

		if (page != Page.MAIN) return
		if (statusLines() == lastStatus) return

		render()

	}

	/**
	 * The two compute lines.
	 *
	 * `load` is what the mainframe is actually spending: the recent peak, not this tick's figure, because
	 * a render lands at an arbitrary point in a tick and `spent` reads as noise. [DcBudget.agePeak] lets
	 * it fall again.
	 *
	 * `demand` is what every compiled program would spend if it all fired on one tick. Over the limit is
	 * not a fault — it means this ship trips if enough of it happens at once, which is a fitting decision,
	 * not an error.
	 */
	private fun statusLines(): List<String> {

		val budget = mainframe.budget

		// seconds, so the sign is rewritten ten times over a reboot rather than two hundred
		if (budget.tripped) return listOf("!! OVERRUN ${budget.rebootRemaining / 20 + 1}s", "rebooting...")

		return listOf(
			"load_${budget.peak}/${budget.limit}",
			"demand_${mainframe.store.totalCost}/${budget.limit}",
		)

	}

	/**
	 * The list page's contents: what is wired in right now, this mainframe's own file, then the way out.
	 *
	 * Only connected machines are listed. A name is kept forever so its program survives being unplugged,
	 * but a console showing every machine that was ever attached is unreadable.
	 */
	private fun entries(): List<String> = mainframe.onlineNames.sorted() + GROUPS_ENTRY + BACK_ENTRY

	private fun step(delta: Int) {

		val size = entries().size

		cursor = ((cursor + delta) % size + size) % size
		render()

	}

	private fun choose(player: Player) {

		when (val entry = entries().getOrNull(cursor) ?: return) {

			BACK_ENTRY -> open(Page.MAIN)

			// the mainframe's own file has no machine behind it, so there is nothing to select or rename
			GROUPS_ENTRY -> mainframe.openGroupsDialog(player)

			else -> {

				selected = entry
				open(Page.SELECTOR)

			}

		}

	}

	private fun open(page: Page) {

		this.page = page

		if (page == Page.LIST) cursor = cursor.coerceIn(0, entries().lastIndex)

		render()

	}

	private fun write(offset: Vec3i, vararg lines: Component) =
		MachineSign.write(mainframe, offset, lines.toList())

	/**
	 * Pads [text] out to a whole line so the sign's own centring lands it against the left edge, and clips
	 * anything that will not fit.
	 *
	 * The padding is derived from the text rather than typed out at the call site, so a value going from
	 * one digit to two eats a space instead of shoving the line right. Signs use a proportional font, so
	 * this is aligned by character count and not by pixel — close enough, and the alternative is a font
	 * metrics table.
	 */
	private fun flush(text: String): String = text.take(LINE_WIDTH).padEnd(LINE_WIDTH)

	/** A line of a name framed as a shell prompt, truncated to leave room for the frame. */
	private fun prompt(name: String): String = ">_ ${name.take(LINE_WIDTH - PROMPT_FRAME)}.sh"

	private fun title(text: String) = Component.text(flush(text)).color(NamedTextColor.AQUA)
	private fun button(text: String) = Component.text(flush(text)).color(NamedTextColor.GOLD)
	private fun value(text: String) = Component.text(flush(text)).color(NamedTextColor.WHITE)
	private fun plain(text: String) = Component.text(flush(text)).color(NamedTextColor.GRAY)
	private fun green(text: String) = Component.text(flush(text)).color(NamedTextColor.GREEN)
	private fun red(text: String) = Component.text(flush(text)).color(NamedTextColor.RED)
	private fun blank() = Component.empty()

	private fun state(text: String, good: Boolean) =
		Component.text(text).color(if (good) NamedTextColor.GREEN else NamedTextColor.DARK_GRAY)

}
