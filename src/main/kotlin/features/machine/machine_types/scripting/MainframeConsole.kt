package dev.diena.anion.features.machine.machine_types.scripting

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

		/** characters of a machine name that fit on one line of a sign */
		private const val LINE_WIDTH = 15

	}

	private enum class Page { MAIN, LIST, SELECTOR }

	private var page = Page.MAIN

	/** where the list page's selector is sat */
	private var cursor = 0

	/** the machine the selector page is showing */
	private var selected: String? = null

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

				write(SIGN_CENTER, green(">_ mainframe.sh   "), green("machines_${mainframe.onlineNames.size}      "), green("servers_NaN     "), blank())
				write(SIGN_LEFT, green(">_ machine_list    "), blank(), blank(), blank())
				write(SIGN_RIGHT, blank(), blank(), blank(), blank())

			}

			Page.LIST -> {

				val entries = entries()

				// a machine unplugging under the cursor shortens the list out from under it
				cursor = cursor.coerceIn(0, entries.lastIndex)

				val entry = entries.getOrNull(cursor)

				write(SIGN_LEFT, green(">_ left              "), blank(), blank(), blank())
				write(SIGN_RIGHT, green(">_ right            "), blank(), blank(), blank())

				val position = value("[${cursor + 1}/${entries.size}]")

				when (entry) {

					BACK_ENTRY -> write(SIGN_CENTER, green(">_ machine.sh     "), blank(), blank(), green("back                "))
					GROUPS_ENTRY -> write(SIGN_CENTER, green(">_ groups.dcprgm"), position, blank(), green("edit                  "))

					else -> {

						val name = entry ?: ""

						write(
							SIGN_CENTER,
							green(">_ ${name.take(10)}.sh"),
							position,
							blank(),
							green("select              "),
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

				val hasFile = name in mainframe.store.fileNames

				write(SIGN_LEFT, green(">_ rename         "), blank(), blank(), blank())
				write(SIGN_RIGHT, green(">_ edit              "), blank(), blank(), blank())

				write(
					SIGN_CENTER,
					green(">_ ${name.take(10)}.sh"),
					green("ls                    "),
					green(if (hasFile) "main.dcprgm        " else ""),
					green("back                "),
				)

			}

		}

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

	private fun title(text: String) = Component.text(text).color(NamedTextColor.AQUA)
	private fun button(text: String) = Component.text(text).color(NamedTextColor.GOLD)
	private fun value(text: String) = Component.text(text).color(NamedTextColor.WHITE)
	private fun plain(text: String) = Component.text(text).color(NamedTextColor.GRAY)
	private fun green(text: String) = Component.text(text).color(NamedTextColor.GREEN)
	private fun blank() = Component.empty()

	private fun state(text: String, good: Boolean) =
		Component.text(text).color(if (good) NamedTextColor.GREEN else NamedTextColor.DARK_GRAY)

}
