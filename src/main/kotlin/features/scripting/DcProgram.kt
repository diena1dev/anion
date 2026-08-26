package dev.diena.anion.features.scripting

/** How an input drives the function it is bound to. */
enum class DcMode {

	/** the value is on for exactly as long as the input is */
	HOLD,

	/** a press flips a latched value, which stays where it was put */
	TOGGLE;

	companion object {

		fun named(name: String): DcMode? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

	}

}

/** One `set <expression> to [target:function] mode [mode]` line, compiled. */
data class DcBinding(

	/** what drives this binding */
	val source: DcExpr,
	/** a group name or a machine name */
	val target: String,
	val function: String,
	val mode: DcMode,

)

/** A compiled `main.dcprgm`: what its machine's inputs do. */
class DcProgram(private val bindings: List<DcBinding>) {

	companion object {

		val EMPTY = DcProgram(emptyList())

	}

	/** inputs this program actually reads. everything else the machine emits is unused. */
	val boundInputs: Set<String> = bindings.flatMapTo(mutableSetOf()) { it.source.inputs }

	/** what one evaluation of every binding costs. what a mainframe's compute budget is charged. */
	val cost: Int = bindings.sumOf { it.source.cost }

	// built once: the runtime hits this every time an input changes
	private val bindingsByInput: Map<String, List<DcBinding>> =
		boundInputs.associateWith { input -> bindings.filter { input in it.source.inputs } }

	/** every binding whose expression reads [input]. */
	fun bindingsReading(input: String): List<DcBinding> = bindingsByInput[input].orEmpty()

	/** every binding driven by exactly [source] in [mode] — one latch's worth. */
	fun bindingsDrivenBy(source: DcExpr, mode: DcMode): List<DcBinding> =
		bindings.filter { it.source == source && it.mode == mode }

	fun describe(): List<String> =
		bindings.map { "${it.source} -> ${it.target}:${it.function} [${it.mode.name.lowercase()}]" }

}

/** A compiled `groups.dcprgm`: which machines are in which group. */
class DcGroups(private val membersByGroup: Map<String, Set<String>>) {

	companion object {

		val EMPTY = DcGroups(emptyMap())

	}

	val names: Set<String> get() = membersByGroup.keys

	/** The machines [target] stands for. A name that is not a group stands for itself. */
	fun membersOf(target: String): Set<String> = membersByGroup[target] ?: setOf(target)

	fun describe(): List<String> =
		membersByGroup.entries.map { (group, members) -> "$group = ${members.sorted().joinToString(", ")}" }

}

/** Something the compiler has to say about one line. */
data class DcIssue(val line: Int, val message: String) {

	override fun toString(): String = "line $line: $message"

}

/** What a compile produced: the artefact, or the reasons there isn't one. */
sealed interface DcResult<out T> {

	data class Ok<T>(val value: T, val warnings: List<DcIssue> = emptyList()) : DcResult<T>
	data class Failed(val errors: List<DcIssue>) : DcResult<Nothing>

}
