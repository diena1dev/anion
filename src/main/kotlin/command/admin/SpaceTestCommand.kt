package dev.diena.anion.command.admin

import dev.astralchroma.processor.annotations.Command
import dev.astralchroma.processor.annotations.CustomType
import dev.astralchroma.processor.annotations.Name
import dev.astralchroma.processor.annotations.Permission
import dev.astralchroma.processor.annotations.Sender
import dev.astralchroma.processor.annotations.Subcommand
import dev.diena.anion.Anion
import dev.diena.anion.Keys
import dev.diena.anion.command.arguments.ValidationGroup
import dev.diena.anion.features.space.validation.CheckGroup
import dev.diena.anion.features.space.validation.GroupReport
import dev.diena.anion.features.space.validation.SpaceValidation
import dev.diena.anion.features.space.validation.Validation
import dev.diena.anion.features.space.validation.ValidationReport
import dev.diena.anion.features.space.validation.checks.NmsLevelChecks
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.CommandSender

/**
 * Runs the space validation harness.
 *
 * Destructive groups write registry entries that cannot be removed until restart, and world folders
 * that are unloaded but left on disk. They are opt-in.
 */
@Command
@Name("spacetest")
@Permission("${Keys.COMMAND_PERMISSION_TREE}.spacetest")
object SpaceTestCommand {

	@Subcommand
	@Permission("${Keys.COMMAND_PERMISSION_TREE}.spacetest.list")
	fun list(

		@Sender sender: CommandSender

	) {

		SpaceValidation

		sender.sendMessage(Component.text("[Space Validation] groups:", NamedTextColor.AQUA))

		for (name in Validation.groupNames()) {

			val group = Validation.group(name) ?: continue
			val marker = if (group.destructive) " (destructive)" else ""

			sender.sendMessage(
				Component.text("  $name", NamedTextColor.WHITE)
					.append(Component.text(marker, NamedTextColor.RED))
					.append(Component.text("  ${group.checks.size} checks — ${group.description}", NamedTextColor.GRAY))
			)

		}

	}

	/** everything that leaves the server as it found it. safe to run on a live server. */
	@Subcommand
	@Permission("${Keys.COMMAND_PERMISSION_TREE}.spacetest.safe")
	fun safe(

		@Sender sender: CommandSender

	) {

		SpaceValidation
		report(sender, SpaceValidation.safeGroups().map { Validation.run(it) })

	}

	@Subcommand
	@Permission("${Keys.COMMAND_PERMISSION_TREE}.spacetest.run")
	fun run(

		@Sender sender: CommandSender,
		@CustomType(ValidationGroup::class) groupName: String,
		confirm: Boolean = false,

	) {

		SpaceValidation

		val group = Validation.group(groupName) ?: return
		if (!confirmed(sender, listOf(group), confirm)) return

		report(sender, listOf(Validation.run(group)))

	}

	@Subcommand
	@Permission("${Keys.COMMAND_PERMISSION_TREE}.spacetest.all")
	fun all(

		@Sender sender: CommandSender,
		confirm: Boolean = false,

	) {

		SpaceValidation

		val groups = SpaceValidation.allGroups()
		if (!confirmed(sender, groups, confirm)) return

		report(sender, groups.map { Validation.run(it) })

	}

	//////////////////////
	///// HELPER FUNCTIONS
	//////////////////////

	/** true when nothing destructive is involved, or the caller has already said yes. */
	private fun confirmed(

		sender: CommandSender,
		groups: List<CheckGroup>,
		confirm: Boolean,

	): Boolean {

		val destructive = groups.filter { it.destructive }
		if (destructive.isEmpty() || confirm) return true

		sender.sendMessage(
			Component.text(
				"WARNING: ${destructive.joinToString(", ") { it.name }} write server state that cannot be undone." +
				"\nRegistry entries persist until restart, and world folders are left on disk." +
				"\nDo not run this on a production server, or with players connected." +
				"\nTo confirm, add 'true' to the end of this command.",
				NamedTextColor.RED,
			)
		)

		return false

	}

	private fun report(

		sender: CommandSender,
		reports: List<GroupReport>,

	) {

		sender.sendMessage(ValidationReport.render(reports))
		ValidationReport.logToConsole(reports, Anion.plugin.logger)

		val folders = NmsLevelChecks.createdWorldFolders()
		if (folders.isEmpty()) return

		sender.sendMessage(
			Component.text("  world folders left on disk, delete them yourself:", NamedTextColor.YELLOW)
		)

		for (folder in folders) sender.sendMessage(Component.text("    $folder", NamedTextColor.GRAY))

	}

}
