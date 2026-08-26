package dev.diena.anion.features.machine.machine_types.scripting.mainframe

import dev.diena.anion.Tasks
import dev.diena.anion.features.scripting.DcIssue
import dev.diena.anion.features.scripting.DcMode
import dev.diena.anion.features.scripting.DcProgrammable
import dev.diena.anion.features.scripting.DcResult
import dev.diena.anion.features.scripting.DcStore
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.input.TextDialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickCallback
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player

/**
 * The mainframe's editor, as Paper dialogs. The signs are the buttons that get you here; this is the
 * part with a keyboard in it.
 *
 * A save that does not compile re-opens the editor holding what the player actually typed, with the
 * errors above it. Losing a program to a typo would be worse than any of the errors.
 */
object MainframeDialogs {

	/** id of the text input, read back off the response view */
	private const val SOURCE_INPUT = "source"
	private const val NAME_INPUT = "name"

	private const val EDITOR_WIDTH = 1024
	private const val EDITOR_HEIGHT = 320
	private const val EDITOR_MAX_LENGTH = 16384
	private const val EDITOR_MAX_LINES = 256

	/** Opens a machine's `main.dcprgm`. [draft] is what to put in the box, defaulting to what is saved. */
	fun openEditor(

		mainframe: MainframeMachine,
		machineName: String,
		player: Player,
		draft: String? = null,
		issues: List<DcIssue> = emptyList(),

	) {

		val emitter = mainframe.machineNamed(machineName) as? DcProgrammable

		val header = mutableListOf<String>()
		header += "dclang v0.3"

		// inputs carry their type, because a program that compares the wrong shape is now a compile error
		emitter?.dataInputs?.takeIf { it.isNotEmpty() }?.let { inputs ->
			header += "available inputs: [${inputs.entries.joinToString(" | ") { (name, type) -> "$name:${type.name.lowercase()}" }}]"
		}
		emitter?.dataFunctions?.takeIf { it.isNotEmpty() }?.let { header += "available functions: [${it.joinToString(" | ")}]" }

		header += "available modes: [${DcMode.entries.joinToString(" | ") { it.name.lowercase() }}]"
		header += "available groups: [${mainframe.groups.names.joinToString(" | ")}]"

		// TODO: for dev env, do not keep- remove with my permission only
		header += "available machines: [${mainframe.machineNames.joinToString(" | ")}]"

		if (emitter == null) header += "machine disconnected | inputs unavailable"

		show(

			player,
			title = "editing [${DcStore.PROGRAM_FILE}] for [$machineName]",
			header = header,
			issues = issues,
			initial = draft ?: mainframe.store.sourceOf(machineName),

		) { source ->

			when (val result = mainframe.store.saveProgram(machineName, source)) {

				is DcResult.Ok -> {

					player.sendMessage(Component.text("saved [${DcStore.PROGRAM_FILE}] for [$machineName]").color(NamedTextColor.GREEN))

					// what this one program costs, and what the mainframe now owes if everything fires at once
					player.sendMessage(
						Component.text(
							"  compute ${result.value.cost} | mainframe demand " +
							"${mainframe.store.totalCost}/${mainframe.budget.limit}"
						).color(NamedTextColor.GRAY)
					)

					for (warning in result.warnings) player.sendMessage(Component.text("  warning $warning").color(NamedTextColor.YELLOW))

					mainframe.console.render()

				}

				is DcResult.Failed -> openEditor(mainframe, machineName, player, source, result.errors)

			}

		}

	}

	/** Opens the mainframe's own `groups.dcprgm`. */
	fun openGroupsEditor(

		mainframe: MainframeMachine,
		player: Player,
		draft: String? = null,
		issues: List<DcIssue> = emptyList(),

	) {

		// connected machines only, the same as the console lists. a name that is merely remembered is not
		// something to write new groups against, even though one already written still compiles.
		val header = listOf(
			"dclang v0.1",
			"available machines: [${mainframe.onlineNames.sorted().joinToString(" | ")}]",
		)

		show(
			player,
			title = "editing [${DcStore.GROUPS_FILE}]",
			header = header,
			issues = issues,
			initial = draft ?: mainframe.store.groupsSource,
		) { source ->

			when (val result = mainframe.store.saveGroups(source)) {

				is DcResult.Ok -> {

					player.sendMessage(Component.text("saved [${DcStore.GROUPS_FILE}]").color(NamedTextColor.GREEN))
					mainframe.console.render()

				}

				is DcResult.Failed -> openGroupsEditor(mainframe, player, source, result.errors)

			}

		}

	}

	/** Asks for a new name for [machineName]. */
	fun openRename(mainframe: MainframeMachine, machineName: String, player: Player) {

		val input = DialogInput.text(NAME_INPUT, Component.text("renaming [$machineName]"))
			.initial(machineName)
			.maxLength(48)
			.width(300)
			.build()

		val base = DialogBase.builder(Component.text(""))
			.body(listOf(DialogBody.plainMessage(Component.text("lowercase letters, digits and underscores"))))
			.inputs(listOf(input))
			.build()

		val confirm = ActionButton.create(
			Component.text("rename"),
			Component.text("refactors every file that contains the old name"),
			100,
			DialogAction.customClick({ view, _ ->

				val newName = view.getText(NAME_INPUT).orEmpty().trim()

				Tasks.runSync {

					val refusal = mainframe.rename(machineName, newName)

					if (refusal == null) player.sendMessage(Component.text("renamed [$machineName] to [$newName]").color(NamedTextColor.GREEN))
					else player.sendMessage(Component.text(refusal).color(NamedTextColor.RED))

				}

			}, ONE_USE),
		)

		val cancel = ActionButton.create(Component.text("exit"), null, 100, null)

		Tasks.runSync {
			player.showDialog(Dialog.create { builder ->
				builder.empty()
					.base(base)
					.type(DialogType.confirmation(confirm, cancel))
			})
		}

	}

	/** The editor dialog itself: header, any errors, one big text box, and Save. */
	private fun show(

		player: Player,
		title: String,
		header: List<String>,
		issues: List<DcIssue>,
		initial: String,
		onSave: (String) -> Unit,

	) {

		val body = mutableListOf<DialogBody>()

		for (line in header) body += DialogBody.plainMessage(Component.text(line).color(NamedTextColor.WHITE))

		if (issues.isNotEmpty()) {

			body += DialogBody.plainMessage(Component.text("compilation error:").color(NamedTextColor.RED))
			for (issue in issues) body += DialogBody.plainMessage(Component.text("  $issue").color(NamedTextColor.RED))

		}

		// text field
		val input = DialogInput.text(SOURCE_INPUT, Component.text(title))
			.initial(initial)
			.maxLength(EDITOR_MAX_LENGTH)
			.width(EDITOR_WIDTH)
			.multiline(TextDialogInput.MultilineOptions.create(EDITOR_MAX_LINES, EDITOR_HEIGHT))
			.build()

		// the frame
		val base = DialogBase.builder(
			Component.text("")) // no title as that is on our editor input frame
			.canCloseWithEscape(false)
			.body(body)
			.inputs(listOf(input))
			.build()
		
		val save = ActionButton.create(
			Component.text("save"),
			Component.text("attempts to compile the dcprgm and saves if successful"),
			80,
			DialogAction.customClick({ view, _ ->

				val source = view.getText(SOURCE_INPUT).orEmpty()

				Tasks.runSync { onSave(source) }

			}, ONE_USE),
		)

		val discard = ActionButton.create(Component.text("exit"), Component.text("exits without attempting to compile or save"), 80, null)

		// confirmation, not multiAction: its two buttons render in the dialog's footer, which is pinned to
		// the screen. a multiAction's action list scrolls with the body, so a small gui scale pushes it off
		Tasks.runSync {
			player.showDialog(Dialog.create { builder ->
				builder.empty()
					.base(base)
					.type(DialogType.confirmation(save, discard))
			})
		}

	}

	/** a button closes its dialog, so one press is all any of these callbacks ever gets */
	private val ONE_USE: ClickCallback.Options = ClickCallback.Options.builder().uses(1).build()

}
