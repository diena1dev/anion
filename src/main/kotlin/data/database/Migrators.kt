package dev.diena.anion.data.database

object Migrators {

	private val all: List<SchemaMigrator> = listOf(
		// empty for now
	)

	fun find(cfName: String, from: Short, to: Short): SchemaMigrator? =
		all.firstOrNull { it.columnFamily == cfName && it.fromVersion == from && it.toVersion == to }

}
