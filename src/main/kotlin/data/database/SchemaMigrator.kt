package dev.diena.anion.data.database

import org.rocksdb.ColumnFamilyHandle
import org.rocksdb.RocksDB

interface SchemaMigrator {
	val columnFamily: String
	val fromVersion: Short
	val toVersion: Short
	fun migrate(db: RocksDB, cf: ColumnFamilyHandle)
}
