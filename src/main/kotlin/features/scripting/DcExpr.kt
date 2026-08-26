package dev.diena.anion.features.scripting

/**
 * A value flowing through a dclang expression. One number, because a machine's inputs already are:
 * `currnt_throttle` is a level, `w` is a key. Anything non-zero is true.
 */
@JvmInline
value class DcValue(val number: Double) {

	val truthy: Boolean get() = number != 0.0

	companion object {

		val TRUE = DcValue(1.0)
		val FALSE = DcValue(0.0)

		fun of(value: Boolean): DcValue = if (value) TRUE else FALSE

	}

	override fun toString(): String = if (number == number.toLong().toDouble()) number.toLong().toString() else number.toString()

}

/**
 * Every operator dclang knows. **This table is the extension point** — a new operator is one entry here
 * and nothing else. The parser reads precedence off it, the evaluator reads [Infix.apply] off it, and
 * the budget reads [Infix.cost] off it.
 *
 * Descriptors are singletons and compare by identity, which is what makes [DcExpr.Binary] and
 * [DcExpr.Unary] usable as map keys.
 */
object DcOperators {

	/** an operator between two expressions. higher [precedence] binds tighter. */
	class Infix(

		val token: String,
		val precedence: Int,
		val cost: Int,
		val apply: (left: DcValue, right: DcValue) -> DcValue,

	) {

		override fun toString(): String = token

	}

	/** an operator in front of one expression. always binds tighter than any [Infix]. */
	class Prefix(

		val token: String,
		val cost: Int,
		val apply: (operand: DcValue) -> DcValue,

	) {

		override fun toString(): String = token

	}

	// dclang v0.2. arithmetic slots in above `and` when it lands: Infix("+", 3, 1) { left, right ->
	// DcValue(left.number + right.number) } and nothing else has to change.
	val infix: List<Infix> = listOf(

		Infix("or", 1, 1) { left, right -> DcValue.of(left.truthy || right.truthy) },
		Infix("and", 2, 1) { left, right -> DcValue.of(left.truthy && right.truthy) },

	)

	val prefix: List<Prefix> = listOf(

		Prefix("not", 1) { operand -> DcValue.of(!operand.truthy) },

	)

	private val infixByToken: Map<String, Infix> = infix.associateBy { it.token }
	private val prefixByToken: Map<String, Prefix> = prefix.associateBy { it.token }

	/** every word an operator claims, so an error can say so instead of "expected a bracketed name" */
	val tokens: Set<String> = infixByToken.keys + prefixByToken.keys

	fun infixFor(token: String): Infix? = infixByToken[token]

	fun prefixFor(token: String): Prefix? = prefixByToken[token]

}

/**
 * An expression over one machine's inputs, compiled.
 *
 * [inputs] and [cost] are computed once at construction — the runtime looks both up per input change,
 * and re-walking the tree for them would make the tree its own worst case.
 */
sealed interface DcExpr {

	/** every input name this expression reads */
	val inputs: Set<String>

	/** how many operations one evaluation costs. what a compute budget is spent on. */
	val cost: Int

	fun evaluate(valueOf: (input: String) -> DcValue): DcValue

	/** an input read by name */
	data class Input(val name: String) : DcExpr {

		override val inputs: Set<String> = setOf(name)
		override val cost: Int = 1

		override fun evaluate(valueOf: (String) -> DcValue): DcValue = valueOf(name)

		override fun toString(): String = "[$name]"

	}

	/** a [DcOperators.Prefix] applied to one operand */
	data class Unary(val operator: DcOperators.Prefix, val operand: DcExpr) : DcExpr {

		override val inputs: Set<String> = operand.inputs
		override val cost: Int = operator.cost + operand.cost

		override fun evaluate(valueOf: (String) -> DcValue): DcValue = operator.apply(operand.evaluate(valueOf))

		override fun toString(): String = "${operator.token} $operand"

	}

	/** a [DcOperators.Infix] applied to two operands */
	data class Binary(val operator: DcOperators.Infix, val left: DcExpr, val right: DcExpr) : DcExpr {

		override val inputs: Set<String> = left.inputs + right.inputs
		override val cost: Int = operator.cost + left.cost + right.cost

		override fun evaluate(valueOf: (String) -> DcValue): DcValue =
			operator.apply(left.evaluate(valueOf), right.evaluate(valueOf))

		override fun toString(): String = "$left ${operator.token} $right"

	}

}
