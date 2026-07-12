package dev.diena.anion.data.database

import dev.diena.anion.features.starship.Starship
import net.minecraft.server.level.ServerLevel
import org.rocksdb.WriteBatch
import java.nio.ByteBuffer
import java.util.UUID

object AnionPersistence {

    // Starship

    fun saveStarship(uuid: UUID, ship: Starship) {
        val batch = WriteBatch()
        batch.put(AnionDatabase.starships, uuidToBytes(uuid), StarshipSerializer.serialize(ship))
        AnionDatabase.write(batch)
        ship.dirty = false
    }

    fun loadStarship(uuid: UUID, world: ServerLevel): Starship? {
        val bytes = AnionDatabase.get(AnionDatabase.starships, uuidToBytes(uuid)) ?: return null
        return StarshipSerializer.deserialize(uuid, bytes, world)
    }

    fun unloadStarship(uuid: UUID) {
        val ship = Starship.loadedStarships.remove(uuid) ?: return
        saveStarship(uuid, ship)
    }

    fun deleteStarship(uuid: UUID) {
        AnionDatabase.delete(AnionDatabase.starships, uuidToBytes(uuid))
        Starship.loadedStarships.remove(uuid)
    }

    /**
     * Scans the starships CF for entries whose origin falls in the given chunk and loads them
     * into [Starship.loadedStarships] if not already present.
     */
    fun loadStarshipsForChunk(world: ServerLevel, chunkX: Int, chunkZ: Int) {
        val iter = AnionDatabase.iterator(AnionDatabase.starships)
	    iter.use { iter ->
		    iter.seekToFirst()
		    while (iter.isValid) {
			    val key = iter.key()
			    val uuid = bytesToUuid(key)
			    if (!Starship.loadedStarships.containsKey(uuid)) {
				    val bytes = iter.value()
				    if (StarshipSerializer.matchesChunk(bytes, world, chunkX, chunkZ)) {
					    val ship = StarshipSerializer.deserialize(uuid, bytes, world)
					    Starship.loadedStarships[uuid] = ship
				    }
			    }
			    iter.next()
		    }
	    }
    }

    /** Saves all in-memory dirty starships. Called on plugin disable. */
    fun flushAll() {
        for ((uuid, ship) in Starship.loadedStarships) {
            if (ship.dirty) saveStarship(uuid, ship)
        }
    }

    // Key encoding

    fun uuidToBytes(uuid: UUID): ByteArray =
        ByteBuffer.allocate(16)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()

    private fun bytesToUuid(bytes: ByteArray): UUID {
        val buf = ByteBuffer.wrap(bytes)
        return UUID(buf.long, buf.long)
    }
}
