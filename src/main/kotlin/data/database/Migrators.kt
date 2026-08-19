package dev.diena.anion.data.database

import org.rocksdb.ColumnFamilyHandle
import org.rocksdb.RocksDB
import org.rocksdb.WriteBatch
import org.rocksdb.WriteOptions
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

object Migrators {

	private val all: List<SchemaMigrator> = listOf(
		MachinesV1ToV2,
	)

	fun find(cfName: String, from: Short, to: Short): SchemaMigrator? =
		all.firstOrNull { it.columnFamily == cfName && it.fromVersion == from && it.toVersion == to }

}

/**
 * Drops the world uuid from stored machines. Machines are only ever loaded through the `machine_chunks`
 * index, whose key already carries the world, so the copy in the blob was never read.
 */
object MachinesV1ToV2 : SchemaMigrator {

	override val columnFamily = "machines"
	override val fromVersion: Short = 1
	override val toVersion: Short = 2

	override fun migrate(db: RocksDB, cf: ColumnFamilyHandle) {

		val batch = WriteBatch()

		db.newIterator(cf).use { iterator ->

			iterator.seekToFirst()

			while (iterator.isValid) {

				rewrite(iterator.value())?.let { batch.put(cf, iterator.key(), it) }
				iterator.next()

			}

		}

		db.write(WriteOptions(), batch)

	}

	private fun rewrite(bytes: ByteArray): ByteArray? {

		return try {

			val input = DataInputStream(ByteArrayInputStream(bytes))

			input.readShort() // v1 schema version
			input.readLong()  // world uuid MSB, dropped
			input.readLong()  // world uuid LSB, dropped

			val originX = input.readInt()
			val originY = input.readInt()
			val originZ = input.readInt()
			val rotation = input.readByte()
			val typeKey = input.readUTF()

			val nbtLength = input.readInt()
			val nbtBytes = ByteArray(nbtLength)
			input.readFully(nbtBytes)

			val output = ByteArrayOutputStream()
			val stream = DataOutputStream(output)

			stream.writeShort(toVersion.toInt())
			stream.writeInt(originX)
			stream.writeInt(originY)
			stream.writeInt(originZ)
			stream.writeByte(rotation.toInt())
			stream.writeUTF(typeKey)
			stream.writeInt(nbtLength)
			stream.write(nbtBytes)
			stream.flush()

			output.toByteArray()

		} catch (_: Exception) {
			null // unreadable row, left at v1 rather than replaced with garbage
		}

	}

}
