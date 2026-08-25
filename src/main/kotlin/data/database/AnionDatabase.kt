package dev.diena.anion.data.database

import dev.diena.anion.data.database.migrators.Migrators
import org.rocksdb.ColumnFamilyDescriptor
import org.rocksdb.ColumnFamilyHandle
import org.rocksdb.ColumnFamilyOptions
import org.rocksdb.DBOptions
import org.rocksdb.RocksDB
import org.rocksdb.RocksIterator
import org.rocksdb.WriteBatch
import org.rocksdb.WriteOptions
import java.io.File
import java.nio.ByteBuffer

const val DB_VERSION: Short         = 1
const val STARSHIPS_VERSION: Short  = 2
const val MACHINES_VERSION: Short   = 2
const val PLAYERS_VERSION: Short    = 1
const val NATIONS_VERSION: Short    = 1

/** Uses RocksDB because of how it caches writes and batch executes them. */
object AnionDatabase {

	private lateinit var db: RocksDB
	private val handles = mutableListOf<ColumnFamilyHandle>()

	// columns are identified by index
	private val CF_NAMES = listOf(

		RocksDB.DEFAULT_COLUMN_FAMILY,
		"metadata".toByteArray(),
		"starships".toByteArray(),
		"machines".toByteArray(),
		"players".toByteArray(),
		"nations".toByteArray(),
		// chunk -> instance indices. keys are world+chunk+uuid, values empty: a chunk load prefix-seeks
		// these instead of scanning every stored starship and machine.
		"starship_chunks".toByteArray(),
		"machine_chunks".toByteArray(),
		// transport components, keyed by the chunk they sit in. cells rather than instances, because a
		// pipe is a block and nothing else.
		"transport".toByteArray(),

	)

	private const val IDX_METADATA        = 1
	private const val IDX_STARSHIPS       = 2
	private const val IDX_MACHINES        = 3
	private const val IDX_PLAYERS         = 4
	private const val IDX_NATIONS         = 5
	private const val IDX_STARSHIP_CHUNKS = 6
	private const val IDX_MACHINE_CHUNKS  = 7
	private const val IDX_TRANSPORT       = 8

	val metadata:  ColumnFamilyHandle get() = handles[IDX_METADATA]
	val starships: ColumnFamilyHandle get() = handles[IDX_STARSHIPS]
	val machines:  ColumnFamilyHandle get() = handles[IDX_MACHINES]

	val players:   ColumnFamilyHandle get() = handles[IDX_PLAYERS]
	val nations:   ColumnFamilyHandle get() = handles[IDX_NATIONS]

	val starshipChunks: ColumnFamilyHandle get() = handles[IDX_STARSHIP_CHUNKS]
	val machineChunks:  ColumnFamilyHandle get() = handles[IDX_MACHINE_CHUNKS]
	val transport:      ColumnFamilyHandle get() = handles[IDX_TRANSPORT]

	fun open(dataFolder: File) {
		RocksDB.loadLibrary()

		val path = File(dataFolder, "db").absolutePath

		val cfDescriptors = CF_NAMES.map { ColumnFamilyDescriptor(it, ColumnFamilyOptions()) }

		val dbOptions = DBOptions()
			.setCreateIfMissing(true)
			.setCreateMissingColumnFamilies(true)

		db = RocksDB.open(dbOptions, path, cfDescriptors, handles)

		runMigrations()
	}

	fun write(batch: WriteBatch) {
		db.write(WriteOptions(), batch)
	}

	fun get(cf: ColumnFamilyHandle, key: ByteArray): ByteArray? = db.get(cf, key)

	fun put(cf: ColumnFamilyHandle, key: ByteArray, value: ByteArray) = db.put(cf, key, value)

	fun delete(cf: ColumnFamilyHandle, key: ByteArray) = db.delete(cf, key)

	fun iterator(cf: ColumnFamilyHandle): RocksIterator = db.newIterator(cf)

	fun close() {
		handles.forEach { it.close() }
		db.close()
	}

	private fun runMigrations() {
		checkVersion("db",        DB_VERSION,        null)
		checkVersion("starships", STARSHIPS_VERSION,  starships)
		checkVersion("machines",  MACHINES_VERSION,   machines)
		checkVersion("players",   PLAYERS_VERSION,    players)
		checkVersion("nations",   NATIONS_VERSION,    nations)
	}

	private fun checkVersion(cfName: String, current: Short, cf: ColumnFamilyHandle?) {
		val key = "${cfName}_version".toByteArray()
		val storedBytes = db.get(metadata, key)

		if (storedBytes == null) {
			db.put(metadata, key, shortToBytes(current))
			return
		}

		val stored = bytesToShort(storedBytes)

		check(stored <= current) {
			"[$cfName] stored schema v$stored > code v$current - downgrade not supported"
		}

		if (stored == current) return

		var version = stored
		while (version < current) {
			val next = (version + 1).toShort()
			val migrator = Migrators.find(cfName, version, next)
				?: error("[$cfName] no migrator registered for v$version -> v$next")
			if (cf != null) migrator.migrate(db, cf)
			version = next
		}

		db.put(metadata, key, shortToBytes(current))
	}

	private fun shortToBytes(value: Short): ByteArray =
		ByteBuffer.allocate(2).putShort(value).array()

	private fun bytesToShort(bytes: ByteArray): Short =
		ByteBuffer.wrap(bytes).short

}
