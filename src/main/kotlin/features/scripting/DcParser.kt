package dev.diena.anion.features.scripting

/**
 * dclang v0.2. Four statements, one per line, every identifier bracketed:
 *
 * ```
 * // comment
 * def_group [name]
 * set [machine] and [machine] in [group] and [group]
 * set ([input] or [input]) and not [input] to [target:function] mode [toggle|hold]
 * ```
 *
 * The input side of a program's `set` is a boolean expression over [DcOperators], grouped with
 * parentheses; `and` there means both at once, not both separately. In `groups.dcprgm` and on the target
 * side of `to`, `and` is still a list separator chaining a cross product, because names are not booleans.
 *
 * The editor draws a header above the program (`--- editing ... ---`, `| available inputs: ...`).
 * Those lines are skipped rather than rejected, so handing the whole buffer back still compiles.
 */
object DcParser {

	/** Compiles a `groups.dcprgm`. [knownMachines] is every name the mainframe has handed out. */
	fun parseGroups(source: String, knownMachines: Set<String>): DcResult<DcGroups> {

		val errors = mutableListOf<DcIssue>()
		val statements = statements(source)

		// groups are collected first, so a set line may reference a group defined below it
		val declared = mutableSetOf<String>()
		for ((line, words) in statements) {

			if (words[0] != "def_group") continue

			val name = words.getOrNull(1)?.let { unwrap(it) }
			if (name == null || words.size != 2) {
				errors += DcIssue(line, "def_group takes one bracketed name, e.g. `def_group [weapons]`")
				continue
			}

			if (name in knownMachines) errors += DcIssue(line, "'$name' is a machine, so it cannot also be a group")

			// a bracketed number is a literal everywhere else, so a group may not take that spelling
			if (name.toDoubleOrNull() != null) errors += DcIssue(line, "group names may not be numbers | '$name' would parse as a value")

			if (!declared.add(name)) errors += DcIssue(line, "group '$name' is already defined")

		}

		val members = mutableMapOf<String, MutableSet<String>>()
		for (name in declared) members[name] = mutableSetOf()

		for ((line, words) in statements) {

			when (words[0]) {

				"def_group" -> continue

				"set" -> {

					val machines = readList(words, 1)
					if (machines == null) {
						errors += DcIssue(line, "expected a bracketed machine name after `set`")
						continue
					}

					val (machineNames, afterMachines) = machines

					if (words.getOrNull(afterMachines) != "in") {
						errors += DcIssue(line, "expected `in` after the machines being assigned")
						continue
					}

					val groups = readList(words, afterMachines + 1)
					if (groups == null || groups.second != words.size) {
						errors += DcIssue(line, "expected bracketed group names after `in`")
						continue
					}

					for (machineName in machineNames) {
						if (machineName !in knownMachines) errors += DcIssue(line, "no machine named '$machineName' is wired to this mainframe")
					}

					for (groupName in groups.first) {

						if (groupName !in declared) {
							errors += DcIssue(line, "group '$groupName' is never defined | add `def_group [$groupName]`")
							continue
						}

						members.getValue(groupName).addAll(machineNames)

					}

				}

				else -> errors += DcIssue(line, "'${words[0]}' is not a dclang statement")

			}

		}

		if (errors.isNotEmpty()) return DcResult.Failed(errors)

		return DcResult.Ok(DcGroups(members.mapValues { it.value.toSet() }))

	}

	/**
	 * Compiles a machine's `main.dcprgm`.
	 *
	 * [availableInputs] is null when the machine is not there to be asked, and its inputs go unchecked —
	 * a program must survive its machine being unplugged, or a reboot would delete every file on the ship.
	 * When it is there, its declared types also let a comparison that cannot work be caught here rather
	 * than in flight.
	 *
	 * [functionsOf] answers what a machine name can be told to do, or null when nothing is there to ask.
	 * A function nothing implements is a warning rather than an error — the readme's whole point is that
	 * a broadcast does not care whether anyone is listening.
	 */
	fun parseProgram(

		source: String,
		availableInputs: Map<String, DcType>?,
		groups: DcGroups,
		knownMachines: Set<String>,
		functionsOf: (machineName: String) -> Set<String>?,

	): DcResult<DcProgram> {

		val errors = mutableListOf<DcIssue>()
		val warnings = mutableListOf<DcIssue>()
		val bindings = mutableListOf<DcBinding>()

		for ((line, words) in statements(source)) {

			if (words[0] != "set") {
				errors += DcIssue(line, "'${words[0]}' is not a dclang statement | a program binds inputs with `set`")
				continue
			}

			val inputs = readExpr(words, 1)
			if (inputs == null) {
				errors += DcIssue(line, "expected an input expression after `set`, e.g. `set ([w] or [a]) and not [sh]` | check your brackets and parentheses")
				continue
			}

			val (expression, afterInputs) = inputs

			if (words.getOrNull(afterInputs) != "to") {
				errors += DcIssue(line, "expected `to` after the input being bound")
				continue
			}

			val targets = readList(words, afterInputs + 1)
			if (targets == null) {
				errors += DcIssue(line, "expected `[target:function]` after `to`")
				continue
			}

			val (calls, afterTargets) = targets

			if (words.getOrNull(afterTargets) != "mode") {
				errors += DcIssue(line, "expected `mode [hold]`, `mode [toggle]` or `mode [push]` at the end of the line")
				continue
			}

			val modeName = words.getOrNull(afterTargets + 1)?.let { unwrap(it) }
			if (modeName == null || afterTargets + 2 != words.size) {
				errors += DcIssue(line, "`mode` takes one bracketed mode, e.g. `mode [hold]`")
				continue
			}

			val mode = DcMode.named(modeName)
			if (mode == null) {
				errors += DcIssue(line, "'$modeName' is not a mode | dclang has ${DcMode.entries.joinToString(", ") { "[${it.name.lowercase()}]" }}")
				continue
			}

			if (availableInputs != null) for (inputName in expression.inputs) {
				if (inputName !in availableInputs) errors += DcIssue(line, "'$inputName' is not an input this machine emits")
			}

			// a type error is caught here whenever the machine is around to declare its shapes. what gets
			// past this is only ever an unplugged machine, and that crashes the mainframe at runtime.
			for (problem in typeErrors(expression, availableInputs)) errors += DcIssue(line, problem)

			for (call in calls) {

				val separator = call.indexOf(':')
				if (separator <= 0 || separator == call.lastIndex) {
					errors += DcIssue(line, "'$call' is not a call | write it as [target:function]")
					continue
				}

				val target = call.substring(0, separator)
				val function = call.substring(separator + 1)

				if (target !in groups.names && target !in knownMachines) {
					errors += DcIssue(line, "'$target' is neither a group nor a machine wired to this mainframe")
					continue
				}

				for (member in groups.membersOf(target)) {

					val functions = functionsOf(member) ?: continue // offline, so there is nothing to check against
					if (function in functions) continue

					warnings += DcIssue(line, "[$member] has no function [:$function] | the call will be ignored")

				}

				bindings += DcBinding(expression, target, function, mode)

			}

		}

		if (errors.isNotEmpty()) return DcResult.Failed(errors)

		return DcResult.Ok(DcProgram(bindings), warnings)

	}

	/////////////////
	///// LEXING
	/////////////////

	/** Every line that says something, as its line number and its words. */
	private fun statements(source: String): List<Pair<Int, List<String>>> =
		source.lines()
			.mapIndexed { index, raw -> (index + 1) to strip(raw) }
			.filter { it.second.isNotEmpty() }
			.map { (line, text) -> line to words(text) }

	/**
	 * Splits a line into words. Brackets are one word; parentheses are their own, spaced or not.
	 *
	 * Anything between quotes is held together, so `["hangar bay"]` is one word rather than two. A text
	 * literal that could not hold a space would be no use for the thing text is for.
	 */
	private fun words(text: String): List<String> {

		val words = mutableListOf<String>()
		val word = StringBuilder()
		var quoted = false

		fun flush() {

			if (word.isEmpty()) return

			words += word.toString()
			word.clear()

		}

		for (character in text) {

			if (character == QUOTE) {

				quoted = !quoted
				word.append(character)

				continue

			}

			// inside quotes nothing is a separator: the whole point is that the text survives verbatim
			if (quoted) {

				word.append(character)
				continue

			}

			when {

				character.isWhitespace() -> flush()

				character == OPEN_CHAR || character == CLOSE_CHAR -> {

					flush()
					words += character.toString()

				}

				else -> word.append(character)

			}

		}

		flush()

		return words

	}

	/** Drops comments, indentation, and the editor's header block. */
	private fun strip(raw: String): String {

		val text = uncommented(raw).trim()
		if (text.startsWith("---") || text.startsWith("|")) return ""

		return text

	}

	/** Everything up to the first `//` that is not inside quotes. */
	// a display line is allowed to say "n/a" or hold a path, and a comment marker inside quotes is text
	private fun uncommented(raw: String): String {

		var quoted = false

		for (index in raw.indices) {

			val character = raw[index]

			if (character == QUOTE) { quoted = !quoted; continue }
			if (quoted) continue

			if (character == '/' && raw.getOrNull(index + 1) == '/') return raw.substring(0, index)

		}

		return raw

	}

	/** The name inside `[brackets]`, or null when [word] is not one. */
	private fun unwrap(word: String): String? {

		if (word.length <= 2) return null
		if (!word.startsWith('[') || !word.endsWith(']')) return null

		return word.substring(1, word.length - 1)

	}

	/**
	 * Reads an expression from [start] by precedence climbing over [DcOperators]. Returns it and where
	 * it stopped — on the first word that is not an operator, which is how `to` and `mode` survive.
	 *
	 * [minPrecedence] is the tightest binding this call will accept; callers start at 0.
	 */
	private fun readExpr(words: List<String>, start: Int, minPrecedence: Int = 0): Pair<DcExpr, Int>? {

		var (left, cursor) = readUnary(words, start) ?: return null

		while (true) {

			val operator = words.getOrNull(cursor)?.let { DcOperators.infixFor(it) } ?: break
			if (operator.precedence < minPrecedence) break

			// left-associative: the right operand may only take operators that bind tighter than this one
			val (right, next) = readExpr(words, cursor + 1, operator.precedence + 1) ?: return null

			left = DcExpr.Binary(operator, left, right)
			cursor = next

		}

		return left to cursor

	}

	/** A parenthesised expression, a prefix operator applied to whatever follows it, or a bare `[input]`. */
	private fun readUnary(words: List<String>, start: Int): Pair<DcExpr, Int>? {

		val word = words.getOrNull(start) ?: return null

		// a group starts precedence over again, which is the whole point of writing one
		if (word == OPEN) {

			val (inner, cursor) = readExpr(words, start + 1) ?: return null
			if (words.getOrNull(cursor) != CLOSE) return null

			return inner to cursor + 1

		}

		val operator = DcOperators.prefixFor(word)
		if (operator != null) {

			val (operand, cursor) = readUnary(words, start + 1) ?: return null
			return DcExpr.Unary(operator, operand) to cursor

		}

		val name = unwrap(word) ?: return null

		return (literal(name) ?: DcExpr.Input(name)) to start + 1

	}

	/** The literal `[content]` spells, or null when it names an input. */
	private fun literal(content: String): DcExpr.Literal? {

		// text is quoted inside the brackets. words are split on whitespace, so a literal cannot hold any.
		if (content.length >= 2 && content.startsWith('"') && content.endsWith('"')) {
			return DcExpr.Literal(DcValue.Str(content.substring(1, content.length - 1)))
		}

		val number = content.toDoubleOrNull() ?: return null
		return DcExpr.Literal(DcValue.Num(number))

	}

	/** Every type problem in [expression], given the machine's [declared] shapes. Empty when unknowable. */
	private fun typeErrors(expression: DcExpr, declared: Map<String, DcType>?): List<String> {

		val problems = mutableListOf<String>()
		collectTypeErrors(expression, declared, problems)

		return problems

	}

	private fun collectTypeErrors(expression: DcExpr, declared: Map<String, DcType>?, into: MutableList<String>) {

		when (expression) {

			is DcExpr.Literal, is DcExpr.Input -> return

			is DcExpr.Unary -> {

				collectTypeErrors(expression.operand, declared, into)
				expression.operator.accepts(expression.operand.typeOf(declared))?.let { into += it }

			}

			is DcExpr.Binary -> {

				collectTypeErrors(expression.left, declared, into)
				collectTypeErrors(expression.right, declared, into)

				expression.operator.accepts(
					expression.left.typeOf(declared),
					expression.right.typeOf(declared),
				)?.let { into += it }

			}

		}

	}

	/** Reads `[a] and [b] and [c]` from [start]. Returns the names and where the list stopped. */
	private fun readList(words: List<String>, start: Int): Pair<List<String>, Int>? {

		val names = mutableListOf<String>()
		var cursor = start

		while (cursor < words.size) {

			names += unwrap(words[cursor]) ?: return null
			cursor++

			if (words.getOrNull(cursor) != "and") break
			cursor++

		}

		if (names.isEmpty()) return null

		return names to cursor

	}

	private const val OPEN = "("
	private const val CLOSE = ")"

	private const val OPEN_CHAR = '('
	private const val CLOSE_CHAR = ')'
	private const val QUOTE = '"'

}
