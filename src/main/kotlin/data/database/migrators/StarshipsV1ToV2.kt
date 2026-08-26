package dev.diena.anion.data.database.migrators

import org.rocksdb.ColumnFamilyHandle
import org.rocksdb.RocksDB
import org.rocksdb.WriteBatch
import org.rocksdb.WriteOptions
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.use

/**
 * Adds the frozen flag to stored starships, written after the yaw. Ships stored at v1 predate the flag
 * and come back unfrozen.
 */
object StarshipsV1ToV2 : SchemaMigrator {

	override val columnFamily = "starships"
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

			val worldMostSignificantBits = input.readLong()
			val worldLeastSignificantBits = input.readLong()

			val originX = input.readInt()
			val originY = input.readInt()
			val originZ = input.readInt()
			val yaw = input.readDouble()

			// everything past the yaw — block count, block entries, machine ref count — is unchanged by
			// this migration and gets copied through verbatim.
			val tail = input.readBytes()

			val output = ByteArrayOutputStream()
			val stream = DataOutputStream(output)

			stream.writeShort(toVersion.toInt())
			stream.writeLong(worldMostSignificantBits)
			stream.writeLong(worldLeastSignificantBits)
			stream.writeInt(originX)
			stream.writeInt(originY)
			stream.writeInt(originZ)
			stream.writeDouble(yaw)
			stream.writeBoolean(false)
			stream.write(tail)
			stream.flush()

			output.toByteArray()

		} catch (_: Exception) {
			null // unreadable row, left at v1 rather than replaced with garbage
		}

	}

}
