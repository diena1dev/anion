package dev.diena.anion.features.space.validation

import dev.diena.anion.features.space.validation.checks.NmsBiomeChecks
import dev.diena.anion.features.space.validation.checks.NmsDimensionTypeChecks
import dev.diena.anion.features.space.validation.checks.NmsLevelChecks
import dev.diena.anion.features.space.validation.checks.NmsRegistryChecks
import dev.diena.anion.features.space.validation.checks.NmsTimelineChecks
import dev.diena.anion.features.space.validation.checks.OrbitDisplayChecks
import dev.diena.anion.features.space.validation.checks.SlipspaceChecks
import dev.diena.anion.features.space.validation.checks.SolarSystemChecks
import dev.diena.anion.features.space.validation.checks.SpaceGenChecks

/**
 * Registers every check group. Object init runs once, so touching this from the command is enough
 * and the harness costs nothing on a server that never runs it.
 */
object SpaceValidation {

	init {

		// pure logic first, they are fast and their failures explain the later ones
		Validation.register(SpaceGenChecks.group)
		Validation.register(SolarSystemChecks.group)
		Validation.register(OrbitDisplayChecks.group)
		Validation.register(SlipspaceChecks.group)

		// then the NMS layer, in dependency order
		Validation.register(NmsDimensionTypeChecks.group)
		Validation.register(NmsRegistryChecks.group)
		Validation.register(NmsBiomeChecks.group)
		Validation.register(NmsTimelineChecks.group)
		Validation.register(NmsLevelChecks.group)

	}

	/** groups that leave the server exactly as they found it. */
	fun safeGroups(): List<CheckGroup> =
		Validation.groupNames().mapNotNull { Validation.group(it) }.filter { !it.destructive }

	fun allGroups(): List<CheckGroup> =
		Validation.groupNames().mapNotNull { Validation.group(it) }

}
