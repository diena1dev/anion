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

/** One `set [input] to [target:function] mode [mode]` line, compiled. */
data class DcBinding(

	/** a group name or a machine name — which one it is gets resolved at dispatch */
	val target: String,
	val function: String,
	val mode: DcMode,

)

/** A compiled `main.dcprgm`: what each of its machine's inputs does. */
class DcProgram(private val bindingsByInput: Map<String, List<DcBinding>>) {

	companion object {

		val EMPTY = DcProgram(emptyMap())

	}

	/** inputs this program actually binds. everything else the machine emits is unused. */
	val boundInputs: Set<String> get() = bindingsByInput.keys

	fun bindingsFor(input: String): List<DcBinding> = bindingsByInput[input].orEmpty()

	fun describe(): List<String> =
		bindingsByInput.entries.flatMap { (input, bindings) ->
			bindings.map { "$input -> ${it.target}:${it.function} [${it.mode.name.lowercase()}]" }
		}

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
