package dev.diena.anion.data.database

import dev.diena.anion.Anion
import dev.diena.anion.data.registry.AnionRegistryKey
import dev.diena.anion.data.registry.registries.AnionRegistries
import dev.diena.anion.extensions.quarterTurns
import dev.diena.anion.extensions.rotationOf
import dev.diena.anion.features.machine.Machine
import net.minecraft.core.Vec3i
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import net.minecraft.server.level.ServerLevel
import java.io.*
import java.util.UUID
import java.util.logging.Level

/**
 * Machines are stored per-instance in their own column family, keyed by machine uuid, and are loaded
 * by the chunk their core block sits in — exactly how [StarshipSerializer] handles ships.
 *
 * Carrier ships are deliberately *not* stored: which ship owns a machine is fully derivable from the
 * ship's blockHashMap, so it is re-established on load by whichever of the two comes up second.
 */
object MachineSerializer {

    fun serialize(machine: Machine): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        dos.writeShort(MACHINES_VERSION.toInt())

        val worldUid = machine.level.world.uid
        dos.writeLong(worldUid.mostSignificantBits)
        dos.writeLong(worldUid.leastSignificantBits)

        dos.writeInt(machine.origin.x)
        dos.writeInt(machine.origin.y)
        dos.writeInt(machine.origin.z)

        dos.writeByte(machine.rotation.quarterTurns)

        // registry path only — MACHINE_TYPE_REGISTRY keys on the path, not the full namespaced key
        dos.writeUTF(machine.namespacedKey.key)

        val tag = CompoundTag()
        machine.saveTo(tag) // base components (buffers) + the type's own saveState()

        val tagBaos = ByteArrayOutputStream()
        NbtIo.write(tag, DataOutputStream(tagBaos))
        val arr = tagBaos.toByteArray()
        dos.writeInt(arr.size)
        dos.write(arr)

        dos.flush()
        return baos.toByteArray()
    }

    /** null when the stored machine type is no longer registered — the entry is skipped, not dropped. */
    fun deserialize(uuid: UUID, bytes: ByteArray, world: ServerLevel): Machine? {
        val dis = DataInputStream(ByteArrayInputStream(bytes))

        val schemaVersion = dis.readShort()
        check(schemaVersion == MACHINES_VERSION) {
            "unsupported machine schema v$schemaVersion (code at v$MACHINES_VERSION)"
        }

        dis.readLong() // world uuid MSB
        dis.readLong() // world uuid LSB

        val originX = dis.readInt()
        val originY = dis.readInt()
        val originZ = dis.readInt()
        val origin = Vec3i(originX, originY, originZ)

        val rotation = rotationOf(dis.readByte().toInt())
        val typeKey = dis.readUTF()

        val factory = AnionRegistries.MACHINE_TYPE_REGISTRY.getValue(AnionRegistryKey(typeKey))
        if (factory == null) {
            // a machine type was removed from the code but its placements are still in the database.
            // leaving the row alone means it comes back if the type is ever re-registered.
            Anion.plugin.logger.log(Level.WARNING, "skipping saved machine $uuid: unknown type '$typeKey'")
            return null
        }

        val nbtLen = dis.readInt()
        val nbtBytes = ByteArray(nbtLen)
        dis.readFully(nbtBytes)
        val tag = NbtIo.read(DataInputStream(ByteArrayInputStream(nbtBytes)))

        return factory().load(uuid, world, origin, rotation, tag)
    }

    /** Peeks at the blob header to check if this machine's core falls in the given chunk. */
    fun matchesChunk(bytes: ByteArray, world: ServerLevel, chunkX: Int, chunkZ: Int): Boolean {
        return try {
            val dis = DataInputStream(ByteArrayInputStream(bytes))
            dis.readShort() // schema version
            val msb = dis.readLong()
            val lsb = dis.readLong()
            val storedWorldUid = UUID(msb, lsb)
            if (storedWorldUid != world.world.uid) return false
            val originX = dis.readInt()
            dis.readInt()   // originY
            val originZ = dis.readInt()
            (originX shr 4) == chunkX && (originZ shr 4) == chunkZ
        } catch (_: Exception) {
            false
        }
    }
}
