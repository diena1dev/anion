package dev.diena.anion.features.machine.machine_types

import dev.diena.anion.features.machine.StructureResult

/**
 * A tank: a procedural multiblock with no fixed [dev.diena.anion.features.machine.BlockSet], so it
 * owns its own structure check via a flood fill over its walls.
 */
abstract class PortedTankMachine(

	displayName: String

) : PortedMachine(displayName, null) {

	// TODO: flood-fill the wall shell from the origin, resolve the enclosed volume, and cache it as
	//       resolvedStructure so ports and revalidation work the same way they do for a BlockSet machine.
	override fun isIntact(): Boolean = false

	// TODO: report every broken wall cell so onStructureChanged() can drain the tank through them.
	override fun structureResult(): StructureResult = StructureResult(false, emptySet())

}
