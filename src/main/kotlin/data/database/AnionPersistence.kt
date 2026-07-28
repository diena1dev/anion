package dev.diena.anion.data.database

import dev.diena.anion.features.machine.Machine
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

        // carried machines leave memory with their ship. detaching first is what makes them eligible
        // for unloadMachine(), which refuses to drop anything still attached to a loaded ship — and
        // their own chunk-unload event has already come and gone, so nothing else would collect them.
        for (machine in ship.machines.detachAll()) unloadMachine(machine.uuid)
    }

    fun deleteStarship(uuid: UUID) {
        AnionDatabase.delete(AnionDatabase.starships, uuidToBytes(uuid))
        Starship.loadedStarships.remove(uuid)?.machines?.detachAll()
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

    // Machine

    fun saveMachine(uuid: UUID, machine: Machine) {
        val batch = WriteBatch()
        batch.put(AnionDatabase.machines, uuidToBytes(uuid), MachineSerializer.serialize(machine))
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

        Machine.activeMachines.remove(uuid)
        saveMachine(uuid, machine)
    }

    fun deleteMachine(uuid: UUID) {
        AnionDatabase.delete(AnionDatabase.machines, uuidToBytes(uuid))
        Machine.activeMachines.remove(uuid)
    }

    /**
     * Scans the machines CF for entries whose core block falls in the given chunk and loads them
     * into [Machine.activeMachines] if not already present.
     */
    fun loadMachinesForChunk(world: ServerLevel, chunkX: Int, chunkZ: Int) {
        val iter = AnionDatabase.iterator(AnionDatabase.machines)
        iter.use { iter ->
            iter.seekToFirst()
            while (iter.isValid) {
                val key = iter.key()
                val uuid = bytesToUuid(key)
                if (!Machine.activeMachines.containsKey(uuid)) {
                    val bytes = iter.value()
                    if (MachineSerializer.matchesChunk(bytes, world, chunkX, chunkZ)) {
                        // load() registers itself in activeMachines and claims its carrier ship
                        MachineSerializer.deserialize(uuid, bytes, world)
                    }
                }
                iter.next()
            }
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
