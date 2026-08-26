package dev.diena.anion.features.scripting

import dev.diena.anion.Anion
import dev.diena.anion.features.machine.machine_types.scripting.mainframe.MainframeMachine

/**
 * The .dcprgm files a mainframe holds, and their compiled form.
 *
 * Source text is what gets saved to disk; compiled programs are rebuilt on load and never persisted.
 */
class DcStore private constructor(private val mainframe: MainframeMachine) {

	companion object {

		fun new(mainframe: MainframeMachine): DcStore = DcStore(mainframe)

		const val GROUPS_FILE = "groups.dcprgm"
		const val PROGRAM_FILE = "main.dcprgm"

	}

	private val sources: MutableMap<String, String> = mutableMapOf()
	private val programs: MutableMap<String, DcProgram> = mutableMapOf()

	var groupsSource: String = ""; private set
	var groups: DcGroups = DcGroups.EMPTY; private set

	/** Every machine name that has a file, whether or not it currently compiles. */
	val fileNames: Set<String> get() = sources.keys

	fun sourceOf(machineName: String): String = sources[machineName].orEmpty()

	fun programOf(machineName: String): DcProgram? = programs[machineName]

	/** Compiles [source] and, if it compiles, makes it this mainframe's groups. */
	fun saveGroups(source: String): DcResult<DcGroups> {

		val result = DcParser.parseGroups(source, mainframe.machineNames)
		if (result !is DcResult.Ok) return result

		groupsSource = source
		groups = result.value

		recompilePrograms()
		mainframe.markProgramsDirty()

		return result

	}

	/** Compiles [source] and, if it compiles, makes it [machineName]'s program. */
	fun saveProgram(machineName: String, source: String): DcResult<DcProgram> {

		val result = compile(machineName, source)
		if (result !is DcResult.Ok) return result

		sources[machineName] = source
		programs[machineName] = result.value

		mainframe.markProgramsDirty()

		return result

	}

	/**
	 * Moves [machineName]'s file to [newName] and rewrites every mention of the old name in every file.
	 *
	 * Both forms a name appears in are rewritten: `[name]` as a group member or a target, and `[name:fn]`
	 * as a call. Callers have already checked that the new name is free.
	 */
	fun rename(machineName: String, newName: String) {

		sources.remove(machineName)?.let { sources[newName] = it }

		groupsSource = rewrite(groupsSource, machineName, newName)
		for ((fileName, source) in sources.toList()) sources[fileName] = rewrite(source, machineName, newName)

		recompile()
		mainframe.markProgramsDirty()

	}

	/** Drops a machine's file entirely. Its name stays assigned; only the program goes. */
	fun forget(machineName: String) {

		sources.remove(machineName)
		programs.remove(machineName)

		mainframe.markProgramsDirty()

	}

	/** Rebuilds every compiled artefact off the stored text. Runs after a load. */
	fun recompile() {

		val result = DcParser.parseGroups(groupsSource, mainframe.machineNames)

		groups = when (result) {

			is DcResult.Ok -> result.value
			is DcResult.Failed -> {

				Anion.plugin.logger.warning("[dcprgm] $GROUPS_FILE no longer compiles: ${result.errors.joinToString("; ")}")
				DcGroups.EMPTY

			}

		}

		recompilePrograms()

	}

	/** The text of every file, for [MainframeMachine]'s save state. */
	fun sourcesForSave(): Map<String, String> = sources.toMap()

	/** Puts saved text back, without compiling it. [recompile] runs once the machine names are restored. */
	fun restore(groupsSource: String, programSources: Map<String, String>) {

		this.groupsSource = groupsSource

		sources.clear()
		sources.putAll(programSources)

	}

	fun debugLines(): List<String> {

		val lines = mutableListOf<String>()

		lines += "$GROUPS_FILE: ${groups.names.size} groups"
		for (line in groups.describe()) lines += "  $line"

		for ((machineName, program) in programs) {

			lines += "$machineName/$PROGRAM_FILE:"
			for (line in program.describe()) lines += "  $line"

		}

		for (machineName in sources.keys - programs.keys) lines += "$machineName/$PROGRAM_FILE: DOES NOT COMPILE"

		return lines

	}

	/** Recompiles every stored program against the current groups. */
	// a program that stops compiling is dropped rather than kept half-live: a group it referenced was
	// deleted, and the mainframe cannot guess what was meant instead
	private fun recompilePrograms() {

		programs.clear()

		for ((machineName, source) in sources) {

			when (val result = compile(machineName, source)) {

				is DcResult.Ok -> programs[machineName] = result.value
				is DcResult.Failed -> Anion.plugin.logger.warning(
					"[dcprgm] '$machineName' $PROGRAM_FILE no longer compiles: ${result.errors.joinToString("; ")}"
				)

			}

		}

	}

	/** Swaps one bracketed identifier for another, leaving anything that merely contains it alone. */
	private fun rewrite(source: String, oldName: String, newName: String): String =
		source
			.replace("[$oldName]", "[$newName]")
			.replace("[$oldName:", "[$newName:")

	private fun compile(machineName: String, source: String): DcResult<DcProgram> {

		val emitter = mainframe.machineNamed(machineName) as? DcProgrammable

		return DcParser.parseProgram(
			source,
			emitter?.dataInputs?.toSet(), // null while it is unplugged: its inputs go unchecked
			groups,
			mainframe.machineNames,
		) { name -> (mainframe.machineNamed(name) as? DcProgrammable)?.dataFunctions?.toSet() }

	}

}
