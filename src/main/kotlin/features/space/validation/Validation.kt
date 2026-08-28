package dev.diena.anion.features.space.validation

import net.minecraft.resources.Identifier
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-server validation harness. The space stack touches frozen NMS registries and constructs
 * ServerLevels, neither of which runs outside a live server, so checks execute in-process against
 * the real thing rather than in a test source set.
 */
object Validation {

	private val groups = linkedMapOf<String, CheckGroup>()

	/** unique per server run, so a group that registers into a frozen registry can run twice */
	private val runCounter = AtomicInteger(0)

	/** an identifier no previous run of this server has used. */
	fun scratchIdentifier(name: String): Identifier =
		Identifier.fromNamespaceAndPath("anion_validation", "${name}_${runCounter.incrementAndGet()}")

	fun register(group: CheckGroup) {

		if (groups.containsKey(group.name)) {
			throw IllegalStateException("Duplicate validation group ${group.name}. Fix your registrations!")
		}

		groups[group.name] = group

	}

	fun groupNames(): Set<String> = groups.keys

	fun group(name: String): CheckGroup? = groups[name]

	/** Runs every registered group, in registration order. */
	fun runAll(): List<GroupReport> = groups.values.map { run(it) }

	/** Runs one group, catching everything so a thrown check cannot take the command down. */
	fun run(group: CheckGroup): GroupReport {

		val results = mutableListOf<CheckResult>()

		for (check in group.checks) {

			val startedAt = System.nanoTime()
			val outcome = execute(check)
			val elapsedMicros = (System.nanoTime() - startedAt) / 1_000

			results += CheckResult(check.name, outcome, elapsedMicros)

		}

		return GroupReport(group.name, results)

	}

	private fun execute(check: Check): Outcome {

		val context = CheckContext()

		return try {

			check.body(context)

			// a check that asserted nothing proved nothing. an early return past every assertion
			// otherwise scores as a pass, which is worse than a failure because nobody looks at it
			if (context.assertions == 0) {
				Outcome.Errored(IllegalStateException("check made no assertions, it returned early or is empty"))
			} else {
				Outcome.Passed
			}

		} catch (signal: SkipSignal) {

			Outcome.Skipped(signal.reason)

		} catch (failure: AssertionFailure) {

			Outcome.Failed(failure.message ?: "assertion failed")

		} catch (exception: Throwable) {

			Outcome.Errored(exception)

		}

	}

}

///////////////
///// THE MODEL
///////////////

class Check(

	val name: String,
	val body: CheckContext.() -> Unit,

)

class CheckGroup(

	val name: String,
	val description: String,
	/** true when the group mutates server state that cannot be undone (registries, world folders) */
	val destructive: Boolean = false,

) {

	val checks = mutableListOf<Check>()

	fun check(

		name: String,
		body: CheckContext.() -> Unit,

	) {

		checks += Check(name, body)

	}

}

sealed interface Outcome {

	object Passed : Outcome
	data class Failed(val message: String) : Outcome
	data class Skipped(val reason: String) : Outcome
	data class Errored(val throwable: Throwable) : Outcome

}

data class CheckResult(

	val name: String,
	val outcome: Outcome,
	val elapsedMicros: Long,

)

data class GroupReport(

	val name: String,
	val results: List<CheckResult>,

) {

	val passed get() = results.count { it.outcome is Outcome.Passed }
	val failed get() = results.count { it.outcome is Outcome.Failed }
	val skipped get() = results.count { it.outcome is Outcome.Skipped }
	val errored get() = results.count { it.outcome is Outcome.Errored }

	val green get() = failed == 0 && errored == 0

}

////////////////
///// ASSERTIONS
////////////////

class AssertionFailure(message: String) : RuntimeException(message)

class SkipSignal(val reason: String) : RuntimeException(reason)

class CheckContext {

	/** how many assertions ran. zero means the check proved nothing. */
	var assertions = 0; private set

	fun require(

		condition: Boolean,
		message: String,

	) {

		assertions++
		if (!condition) throw AssertionFailure(message)

	}

	fun requireEquals(

		expected: Any?,
		actual: Any?,
		what: String,

	) {

		assertions++
		if (expected != actual) throw AssertionFailure("$what: expected <$expected>, got <$actual>")

	}

	fun requireNotNull(

		value: Any?,
		what: String,

	) {

		assertions++
		if (value == null) throw AssertionFailure("$what was null")

	}

	/** passes only if [body] throws. returns the throwable so the caller can inspect it. */
	fun requireThrows(

		what: String,
		body: () -> Unit,

	): Throwable {

		assertions++

		try {
			body()
		} catch (thrown: Throwable) {
			return thrown
		}

		throw AssertionFailure("$what: expected a throw, nothing was thrown")

	}

	/** floating point comparison, for the orbital and geometry checks. */
	fun requireNear(

		expected: Double,
		actual: Double,
		tolerance: Double,
		what: String,

	) {

		assertions++
		if (kotlin.math.abs(expected - actual) > tolerance) {
			throw AssertionFailure("$what: expected <$expected> +/- $tolerance, got <$actual>")
		}

	}

	/** abandons the check without failing it. for anything that is not built yet. */
	fun skip(reason: String): Nothing = throw SkipSignal(reason)

}
