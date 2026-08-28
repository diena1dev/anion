package dev.diena.anion.features.space.validation

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.format.NamedTextColor

/** Renders [GroupReport]s for chat and console. */
object ValidationReport {

	fun render(

		reports: List<GroupReport>

	): Component {

		val lines = Component.text()

		lines.append(text("[Space Validation]", NamedTextColor.AQUA))

		for (report in reports) {

			lines.append(Component.newline())
			lines.append(renderGroup(report))

		}

		lines.append(Component.newline())
		lines.append(renderTotals(reports))

		return lines.build()

	}

	private fun renderGroup(

		report: GroupReport

	): Component {

		val header = text()
			.append(text(if (report.green) "  PASS " else "  FAIL ", if (report.green) NamedTextColor.GREEN else NamedTextColor.RED))
			.append(text(report.name, NamedTextColor.WHITE))
			.append(text("  ${report.passed}/${report.results.size}", NamedTextColor.GRAY))

		if (report.skipped > 0) header.append(text("  (${report.skipped} pending)", NamedTextColor.YELLOW))

		// only the interesting lines get expanded, a green run stays one line per group
		for (result in report.results) {

			when (val outcome = result.outcome) {

				is Outcome.Passed -> continue

				is Outcome.Failed -> header
					.append(Component.newline())
					.append(text("      x ${result.name}: ${outcome.message}", NamedTextColor.RED))

				is Outcome.Errored -> header
					.append(Component.newline())
					.append(text("      ! ${result.name}: ${describe(outcome.throwable)}", NamedTextColor.DARK_RED))

				is Outcome.Skipped -> header
					.append(Component.newline())
					.append(text("      - ${result.name}: ${outcome.reason}", NamedTextColor.YELLOW))

			}

		}

		return header.build()

	}

	private fun renderTotals(

		reports: List<GroupReport>

	): Component {

		val passed = reports.sumOf { it.passed }
		val failed = reports.sumOf { it.failed }
		val skipped = reports.sumOf { it.skipped }
		val errored = reports.sumOf { it.errored }

		val colour = if (failed == 0 && errored == 0) NamedTextColor.GREEN else NamedTextColor.RED

		return text("  $passed passed, $failed failed, $errored errored, $skipped pending", colour)

	}

	private fun describe(throwable: Throwable): String =
		"${throwable::class.simpleName}: ${throwable.message ?: "no message"}"

	/** plain text, for the console log where the stack trace is worth keeping. */
	fun logToConsole(

		reports: List<GroupReport>,
		logger: java.util.logging.Logger,

	) {

		for (report in reports) {

			logger.info("[space-validation] ${if (report.green) "PASS" else "FAIL"} ${report.name} ${report.passed}/${report.results.size}")

			for (result in report.results) {

				when (val outcome = result.outcome) {
					is Outcome.Passed -> {}
					is Outcome.Failed -> logger.warning("[space-validation]   x ${result.name}: ${outcome.message}")
					is Outcome.Skipped -> logger.info("[space-validation]   - ${result.name}: ${outcome.reason}")
					is Outcome.Errored -> logger.log(java.util.logging.Level.SEVERE, "[space-validation]   ! ${result.name}", outcome.throwable)
				}

			}

		}

	}

}
