package dev.diena.anion.data.database.migrators

import dev.diena.anion.data.database.migrators.SchemaMigrator

object Migrators {

	private val all: List<SchemaMigrator> = listOf(
		MachinesV1ToV2,
	)

	fun find(cfName: String, from: Short, to: Short): SchemaMigrator? =
		all.firstOrNull { it.columnFamily == cfName && it.fromVersion == from && it.toVersion == to }

}
