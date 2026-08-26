package dev.diena.anion.features.scripting

/** What shape a value is. Declared per input, so the compiler can reject a comparison that cannot work. */
enum class DcType { NUM, STR, VEC, LST }

/**
 * A value flowing through a dclang expression.
 *
 * Anything non-empty and non-zero is true, so a bare `[input]` still reads as a switch whatever it holds.
 */
sealed interface DcValue {

	val type: DcType

	/** whether this reads as true to `and`, `or`, `not` and `mode` */
	val truthy: Boolean

	/** a number: a key, a throttle, a count */
	data class Num(val number: Double) : DcValue {

		override val type: DcType get() = DcType.NUM
		override val truthy: Boolean get() = number != 0.0

		override fun toString(): String =
			if (number == number.toLong().toDouble()) number.toLong().toString() else number.toString()

	}

	/** text: a label, a status, a line on a display */
	data class Str(val text: String) : DcValue {

		override val type: DcType get() = DcType.STR
		override val truthy: Boolean get() = text.isNotEmpty()

		override fun toString(): String = text

	}

	/** a position. its own type rather than three numbers, because starship automation wants it to be */
	data class Vec(val x: Double, val y: Double, val z: Double) : DcValue {

		override val type: DcType get() = DcType.VEC

		// a position that exists is true. magnitude would make the world origin read as false
		override val truthy: Boolean get() = true

		override fun toString(): String = "($x, $y, $z)"

	}

	/** many values: radar contacts, buffer contents, faults */
	data class Lst(val items: List<DcValue>) : DcValue {

		override val type: DcType get() = DcType.LST
		override val truthy: Boolean get() = items.isNotEmpty()

		override fun toString(): String = items.joinToString(", ", "[", "]")

	}

	companion object {

		val TRUE = Num(1.0)
		val FALSE = Num(0.0)

		fun of(value: Boolean): DcValue = if (value) TRUE else FALSE

		/** what an input of [type] reads as before the machine has ever reported one */
		fun zeroOf(type: DcType): DcValue = when (type) {

			DcType.NUM -> FALSE
			DcType.STR -> Str("")
			DcType.VEC -> Vec(0.0, 0.0, 0.0)
			DcType.LST -> Lst(emptyList())

		}

	}

	/** This value's number, or a [DcTypeError] naming [operator]. */
	fun asNumber(operator: String): Double =
		(this as? Num)?.number ?: throw DcTypeError("`$operator` needs a number, got ${type.name.lowercase()}")

	/** This value's text, or a [DcTypeError] naming [operator]. */
	fun asText(operator: String): String =
		(this as? Str)?.text ?: throw DcTypeError("`$operator` needs text, got ${type.name.lowercase()}")

}

/** The compute budget ran out mid-evaluation. Caught by [DcRuntime]; never escapes it. */
internal class DcOverrun : RuntimeException(null, null, false, false)

/** An operator got a value it cannot work on. Caught by [DcRuntime]; never escapes it. */
internal class DcTypeError(message: String) : RuntimeException(message, null, false, false)

/**
 * What an expression is evaluated against: the machine's current input values, and the budget the work
 * is charged to.
 */
class DcEval(

	private val values: Map<String, DcValue>,
	private val budget: DcBudget,

) {

	fun valueOf(input: String): DcValue = values[input] ?: DcValue.FALSE

	/** Charges [operations]. Throws once the allowance is gone, which unwinds the whole evaluation. */
	fun charge(operations: Int) {

		if (!budget.charge(operations)) throw DcOverrun()

	}

}

/**
 * Every operator dclang knows. **This table is the extension point** — a new operator is one entry here
 * and nothing else. The parser reads precedence and type rules off it, the evaluator reads [Infix.apply],
 * and the budget reads [Infix.costOf].
 *
 * Descriptors are singletons and compare by identity, which is what makes [DcExpr.Binary] and
 * [DcExpr.Unary] usable as map keys.
 */
object DcOperators {

	// TODO: tune. 8 is a guess — if aggregates turn out too cheap to warn on a program that trips in
	//       flight, raise it. Read real numbers off `/machine debug` peak spend first.
	const val AGGREGATE_COST = 8

	/** an operator between two expressions. higher [precedence] binds tighter. */
	class Infix(

		val token: String,
		val precedence: Int,
		val cost: Int,
		/** result type, or null when it is whatever the operands are */
		val result: DcType?,
		val apply: (left: DcValue, right: DcValue) -> DcValue,
		/** what one application actually costs. defaults to the flat [cost]. */
		val costOf: (left: DcValue, right: DcValue) -> Int = { _, _ -> cost },
		/** null when these operand types are fine, else why not. A null type is not knowable, so allow it. */
		val accepts: (left: DcType?, right: DcType?) -> String? = { _, _ -> null },

	) {

		override fun toString(): String = token

	}

	/** an operator in front of one expression. always binds tighter than any [Infix]. */
	class Prefix(

		val token: String,
		val cost: Int,
		/** result type, or null when it is whatever the operand is */
		val result: DcType?,
		val apply: (operand: DcValue) -> DcValue,
		val costOf: (operand: DcValue) -> Int = { cost },
		val accepts: (operand: DcType?) -> String? = { null },

	) {

		override fun toString(): String = token

	}

	private fun numbersOnly(token: String): (DcType?, DcType?) -> String? = { left, right ->

		val offender = listOfNotNull(left, right).firstOrNull { it != DcType.NUM }
		if (offender == null) null else "`$token` needs numbers, got ${offender.name.lowercase()}"

	}

	val infix: List<Infix> = listOf(

		Infix("or", 1, 1, DcType.NUM, apply = { left, right -> DcValue.of(left.truthy || right.truthy) }),
		Infix("and", 2, 1, DcType.NUM, apply = { left, right -> DcValue.of(left.truthy && right.truthy) }),

		// equality works on anything, including across types, where it is simply false
		Infix("=", 3, 1, DcType.NUM, apply = { left, right -> DcValue.of(left == right) }),
		Infix("!=", 3, 1, DcType.NUM, apply = { left, right -> DcValue.of(left != right) }),

		Infix("<", 3, 1, DcType.NUM,
			apply = { left, right -> DcValue.of(left.asNumber("<") < right.asNumber("<")) },
			accepts = numbersOnly("<")),
		Infix(">", 3, 1, DcType.NUM,
			apply = { left, right -> DcValue.of(left.asNumber(">") > right.asNumber(">")) },
			accepts = numbersOnly(">")),
		Infix("<=", 3, 1, DcType.NUM,
			apply = { left, right -> DcValue.of(left.asNumber("<=") <= right.asNumber("<=")) },
			accepts = numbersOnly("<=")),
		Infix(">=", 3, 1, DcType.NUM,
			apply = { left, right -> DcValue.of(left.asNumber(">=") >= right.asNumber(">=")) },
			accepts = numbersOnly(">=")),

		// the one operator that is not number-only: two strings join. mixing is an error rather than a
		// coercion, because `[count] + [label]` is always a mistake and never a request
		Infix("+", 4, 1, null,
			apply = { left, right ->

				if (left is DcValue.Str || right is DcValue.Str) DcValue.Str(left.asText("+") + right.asText("+"))
				else DcValue.Num(left.asNumber("+") + right.asNumber("+"))

			},
			accepts = { left, right ->

				val types = listOfNotNull(left, right).toSet()
				when {

					types.all { it == DcType.NUM } -> null
					types.all { it == DcType.STR } -> null
					else -> "`+` joins two numbers or two strings, got ${types.joinToString(" and ") { it.name.lowercase() }}"

				}

			}),

		Infix("-", 4, 1, DcType.NUM,
			apply = { left, right -> DcValue.Num(left.asNumber("-") - right.asNumber("-")) },
			accepts = numbersOnly("-")),
		Infix("*", 5, 1, DcType.NUM,
			apply = { left, right -> DcValue.Num(left.asNumber("*") * right.asNumber("*")) },
			accepts = numbersOnly("*")),

		// dividing by zero yields zero: deterministic, and unremarkable enough not to be worth a failure
		Infix("/", 5, 1, DcType.NUM,
			apply = { left, right ->

				val divisor = right.asNumber("/")
				DcValue.Num(if (divisor == 0.0) 0.0 else left.asNumber("/") / divisor)

			},
			accepts = numbersOnly("/")),

	)

	// aggregates degrade rather than fail on a non-list: a machine that turns a scalar input into a list
	// between versions must not break every program that read it
	private val elementCost: (DcValue) -> Int = { operand -> AGGREGATE_COST + ((operand as? DcValue.Lst)?.items?.size ?: 0) }

	val prefix: List<Prefix> = listOf(

		Prefix("not", 1, DcType.NUM, apply = { operand -> DcValue.of(!operand.truthy) }),

		Prefix("count", AGGREGATE_COST, DcType.NUM,
			apply = { operand -> DcValue.Num(((operand as? DcValue.Lst)?.items?.size ?: 1).toDouble()) },
			costOf = elementCost),

		Prefix("any", AGGREGATE_COST, DcType.NUM,
			apply = { operand ->

				val list = operand as? DcValue.Lst
				if (list == null) DcValue.of(operand.truthy) else DcValue.of(list.items.any { it.truthy })

			},
			costOf = elementCost),

		Prefix("all", AGGREGATE_COST, DcType.NUM,
			apply = { operand ->

				val list = operand as? DcValue.Lst
				if (list == null) DcValue.of(operand.truthy) else DcValue.of(list.items.all { it.truthy })

			},
			costOf = elementCost),

		// the only way to get text out of a number or a position, since `+` refuses to coerce
		Prefix("text", 1, DcType.STR, apply = { operand -> DcValue.Str(operand.toString()) }),

	)

	private val infixByToken: Map<String, Infix> = infix.associateBy { it.token }
	private val prefixByToken: Map<String, Prefix> = prefix.associateBy { it.token }

	/** every word an operator claims */
	val tokens: Set<String> = infixByToken.keys + prefixByToken.keys

	fun infixFor(token: String): Infix? = infixByToken[token]

	fun prefixFor(token: String): Prefix? = prefixByToken[token]

}

/**
 * An expression over one machine's inputs, compiled.
 *
 * [inputs] and [cost] are computed once at construction — the runtime looks both up per input change,
 * and re-walking the tree for them would make the tree its own worst case. [cost] is a static estimate
 * for the compile-time warning; what actually gets charged is measured during [evaluate].
 */
sealed interface DcExpr {

	/** every input name this expression reads */
	val inputs: Set<String>

	/** estimated operations one evaluation costs. what the compile-time budget warning is based on. */
	val cost: Int

	fun evaluate(eval: DcEval): DcValue

	/** This expression's type given the machine's [declared] input types, or null when not knowable. */
	fun typeOf(declared: Map<String, DcType>?): DcType?

	/** a value written into the program */
	data class Literal(val value: DcValue) : DcExpr {

		override val inputs: Set<String> = emptySet()
		override val cost: Int = 1

		override fun evaluate(eval: DcEval): DcValue {

			eval.charge(1)
			return value

		}

		override fun typeOf(declared: Map<String, DcType>?): DcType = value.type

		override fun toString(): String = if (value is DcValue.Str) "[\"$value\"]" else "[$value]"

	}

	/** an input read by name */
	data class Input(val name: String) : DcExpr {

		override val inputs: Set<String> = setOf(name)
		override val cost: Int = 1

		override fun evaluate(eval: DcEval): DcValue {

			eval.charge(1)
			return eval.valueOf(name)

		}

		override fun typeOf(declared: Map<String, DcType>?): DcType? = declared?.get(name)

		override fun toString(): String = "[$name]"

	}

	/** a [DcOperators.Prefix] applied to one operand */
	data class Unary(val operator: DcOperators.Prefix, val operand: DcExpr) : DcExpr {

		override val inputs: Set<String> = operand.inputs
		override val cost: Int = operator.cost + operand.cost

		override fun evaluate(eval: DcEval): DcValue {

			val value = operand.evaluate(eval)
			eval.charge(operator.costOf(value))

			return operator.apply(value)

		}

		override fun typeOf(declared: Map<String, DcType>?): DcType? = operator.result ?: operand.typeOf(declared)

		override fun toString(): String = "${operator.token} $operand"

	}

	/** a [DcOperators.Infix] applied to two operands */
	data class Binary(val operator: DcOperators.Infix, val left: DcExpr, val right: DcExpr) : DcExpr {

		override val inputs: Set<String> = left.inputs + right.inputs
		override val cost: Int = operator.cost + left.cost + right.cost

		override fun evaluate(eval: DcEval): DcValue {

			val leftValue = left.evaluate(eval)
			val rightValue = right.evaluate(eval)

			eval.charge(operator.costOf(leftValue, rightValue))

			return operator.apply(leftValue, rightValue)

		}

		override fun typeOf(declared: Map<String, DcType>?): DcType? =
			operator.result ?: left.typeOf(declared) ?: right.typeOf(declared)

		override fun toString(): String = "($left ${operator.token} $right)"

	}

}
