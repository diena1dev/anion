package dev.diena.anion.data.database

import dev.diena.anion.features.machine.Machine
import dev.diena.anion.features.machine.MachineIndex
import dev.diena.anion.features.starship.Starship
import net.minecraft.core.Vec3i
import net.minecraft.server.level.ServerLevel
import org.bukkit.Bukkit
import org.rocksdb.ColumnFamilyHandle
import org.rocksdb.WriteBatch
import java.nio.ByteBuffer
import java.util.UUID

object AnionPersistence {

    private val NO_VALUE = ByteArray(0)

    // Starship

    fun saveStarship(uuid: UUID, ship: Starship) {
        val batch = WriteBatch()
        val key = uuidToBytes(uuid)
        val worldUid = ship.level.world.uid

        // drop the stale chunk row first — a ship's origin chunk changes as it flies
        val previousOrigin = AnionDatabase.get(AnionDatabase.starships, key)?.let { StarshipSerializer.readOrigin(it) }
        if (previousOrigin != null) {
            batch.delete(AnionDatabase.starshipChunks, chunkIndexKey(worldUid, previousOrigin, uuid))
        }

        batch.put(AnionDatabase.starships, key, StarshipSerializer.serialize(ship))
        batch.put(AnionDatabase.starshipChunks, chunkIndexKey(worldUid, ship.origin, uuid), NO_VALUE)

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

        // carried machines leave memory with their ship. detaching first is what makes them eligible
        // for unloadMachine(), which refuses to drop anything still attached to a loaded ship — and
        // their own chunk-unload event has already come and gone, so nothing else would collect them.
        for (machine in ship.machines.detachAll()) unloadMachine(machine.uuid)
    }

    fun deleteStarship(uuid: UUID) {
        val key = uuidToBytes(uuid)
        val ship = Starship.loadedStarships.remove(uuid)

        val storedOrigin = AnionDatabase.get(AnionDatabase.starships, key)?.let { StarshipSerializer.readOrigin(it) }
        if (storedOrigin != null && ship != null) {
            AnionDatabase.delete(AnionDatabase.starshipChunks, chunkIndexKey(ship.level.world.uid, storedOrigin, uuid))
        }

        AnionDatabase.delete(AnionDatabase.starships, key)
        ship?.machines?.detachAll()
    }

    /** Loads every starship whose origin falls in the given chunk, via the `starship_chunks` index. */
    fun loadStarshipsForChunk(world: ServerLevel, chunkX: Int, chunkZ: Int) {
        for (uuid in indexedUuids(AnionDatabase.starshipChunks, world.world.uid, chunkX, chunkZ)) {
            if (Starship.loadedStarships.containsKey(uuid)) continue

            val bytes = AnionDatabase.get(AnionDatabase.starships, uuidToBytes(uuid)) ?: continue
            Starship.loadedStarships[uuid] = StarshipSerializer.deserialize(uuid, bytes, world)
        }
    }

    // Machine

    fun saveMachine(uuid: UUID, machine: Machine) {
        val batch = WriteBatch()
        val key = uuidToBytes(uuid)
        val worldUid = machine.level.world.uid

        // drop the stale chunk row first — a carried machine's origin chunk changes as its ship moves
        val previousOrigin = AnionDatabase.get(AnionDatabase.machines, key)?.let { MachineSerializer.readOrigin(it) }
        if (previousOrigin != null) {
            batch.delete(AnionDatabase.machineChunks, chunkIndexKey(worldUid, previousOrigin, uuid))
        }

        batch.put(AnionDatabase.machines, key, MachineSerializer.serialize(machine))
        batch.put(AnionDatabase.machineChunks, chunkIndexKey(worldUid, machine.origin, uuid), NO_VALUE)

        AnionDatabase.write(batch)
        machine.dirty = false
    }

    fun loadMachine(uuid: UUID, world: ServerLevel): Machine? {
        val bytes = AnionDatabase.get(AnionDatabase.machines, uuidToBytes(uuid)) ?: return null
        return MachineSerializer.deserialize(uuid, bytes, world)
    }

    /** Persists [uuid]'s machine and drops it from memory. The machine still exists, it is just not loaded. */
    // deliberately does NOT run onDisassemble() — that hook means "this machine was destroyed".
    // TODO: machines holding attached entities will need an onUnload() hook to shut them down here.
    fun unloadMachine(uuid: UUID) {
        val machine = Machine.activeMachines[uuid] ?: return

        // a carried machine stays loaded as long as its ship is: ships unload on their own origin
        // chunk, so this machine's chunk can go down while the ship keeps moving. dropping it here
        // would freeze it at the origin it happened to have at unload time and save that.
        if (machine.starship != null) return

        MachineIndex.unregister(machine)
        Machine.activeMachines.remove(uuid)
        saveMachine(uuid, machine)
    }

    /** Erases [uuid]'s machine. [level] is the world it was assembled in, needed to find its index row. */
    fun deleteMachine(uuid: UUID, level: ServerLevel) {
        val key = uuidToBytes(uuid)

        val storedOrigin = AnionDatabase.get(AnionDatabase.machines, key)?.let { MachineSerializer.readOrigin(it) }
        if (storedOrigin != null) {
            AnionDatabase.delete(AnionDatabase.machineChunks, chunkIndexKey(level.world.uid, storedOrigin, uuid))
        }

        AnionDatabase.delete(AnionDatabase.machines, key)
        Machine.activeMachines.remove(uuid)
    }

    /** Loads every machine whose core block falls in the given chunk, via the `machine_chunks` index. */
    fun loadMachinesForChunk(world: ServerLevel, chunkX: Int, chunkZ: Int) {
        for (uuid in indexedUuids(AnionDatabase.machineChunks, world.world.uid, chunkX, chunkZ)) {
            if (Machine.activeMachines.containsKey(uuid)) continue

            // deserialize() registers the machine in activeMachines and claims its carrier ship
            val bytes = AnionDatabase.get(AnionDatabase.machines, uuidToBytes(uuid)) ?: continue
            MachineSerializer.deserialize(uuid, bytes, world)
        }
    }

    /** Saves all in-memory dirty starships and machines. Called on plugin disable. */
    fun flushAll() {
        for ((uuid, ship) in Starship.loadedStarships) {
            if (ship.dirty) saveStarship(uuid, ship)
        }
        for ((uuid, machine) in Machine.activeMachines) {
            if (machine.dirty) saveMachine(uuid, machine)
        }
    }

    // Chunk index

    /** world uuid (16) | chunk x (4) | chunk z (4) | instance uuid (16) */
    private const val CHUNK_PREFIX_SIZE = 24

    private fun chunkIndexKey(worldUid: UUID, origin: Vec3i, uuid: UUID): ByteArray =
        ByteBuffer.allocate(CHUNK_PREFIX_SIZE + 16)
            .putLong(worldUid.mostSignificantBits)
            .putLong(worldUid.leastSignificantBits)
            .putInt(origin.x shr 4)
            .putInt(origin.z shr 4)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()

    private fun chunkPrefix(worldUid: UUID, chunkX: Int, chunkZ: Int): ByteArray =
        ByteBuffer.allocate(CHUNK_PREFIX_SIZE)
            .putLong(worldUid.mostSignificantBits)
            .putLong(worldUid.leastSignificantBits)
            .putInt(chunkX)
            .putInt(chunkZ)
            .array()

    /** Every instance uuid indexed under the given chunk. Prefix scan, not a full column scan. */
    private fun indexedUuids(

        index: ColumnFamilyHandle,
        worldUid: UUID,
        chunkX: Int,
        chunkZ: Int,

    ): List<UUID> {

        val prefix = chunkPrefix(worldUid, chunkX, chunkZ)
        val found = mutableListOf<UUID>()

        AnionDatabase.iterator(index).use { iterator ->

            iterator.seek(prefix)

            while (iterator.isValid) {

                val key = iterator.key()
                if (key.size != prefix.size + 16) break
                if (!key.copyOfRange(0, prefix.size).contentEquals(prefix)) break // walked past this chunk

                found += bytesToUuid(key.copyOfRange(prefix.size, key.size))
                iterator.next()

            }

        }

        return found

    }

    /**
     * Fills the chunk indices from the instance columns when they are empty. Runs once at startup so a
     * database written before the indices existed still resolves its chunks.
     */
    fun rebuildChunkIndices() {

        rebuildChunkIndex(AnionDatabase.starships, AnionDatabase.starshipChunks) { StarshipSerializer.readOrigin(it) }
        rebuildChunkIndex(AnionDatabase.machines, AnionDatabase.machineChunks) { MachineSerializer.readOrigin(it) }

    }

    private fun rebuildChunkIndex(

        instances: ColumnFamilyHandle,
        index: ColumnFamilyHandle,
        originOf: (ByteArray) -> Vec3i?,

    ) {

        AnionDatabase.iterator(index).use { iterator ->
            iterator.seekToFirst()
            if (iterator.isValid) return // already populated
        }

        // the world uuid is not in the blob, so a rebuilt row is filed under every loaded world and the
        // wrong ones simply never resolve to a stored instance.
        val worldUids = Bukkit.getWorlds().map { it.uid }
        if (worldUids.isEmpty()) return

        val batch = WriteBatch()

        AnionDatabase.iterator(instances).use { iterator ->

            iterator.seekToFirst()

            while (iterator.isValid) {

                val origin = originOf(iterator.value())
                if (origin != null) {

                    val uuid = bytesToUuid(iterator.key())
                    for (worldUid in worldUids) batch.put(index, chunkIndexKey(worldUid, origin, uuid), NO_VALUE)

                }

                iterator.next()

            }

        }

        AnionDatabase.write(batch)

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
