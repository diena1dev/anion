package dev.diena.anion.data.database

import dev.diena.anion.features.starship.Starship
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtUtils
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.state.BlockState
import java.io.*
import java.util.UUID

// and we all say "thank you, Claude", because surely nothing ill will come of me not actually learning how to use a database
object StarshipSerializer {

    fun serialize(ship: Starship): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)

        dos.writeShort(STARSHIPS_VERSION.toInt())

        val worldUid = ship.level.world.uid
        dos.writeLong(worldUid.mostSignificantBits)
        dos.writeLong(worldUid.leastSignificantBits)

        dos.writeInt(ship.origin.x)
        dos.writeInt(ship.origin.y)
        dos.writeInt(ship.origin.z)

        dos.writeDouble(ship.yaw)

        dos.writeInt(ship.blockHashMap.size)
        for ((vec, state) in ship.blockHashMap) {
            val relLong = BlockPos.asLong(
                vec.x - ship.origin.x,
                vec.y - ship.origin.y,
                vec.z - ship.origin.z
            )
            dos.writeLong(relLong)

            val tag = NbtUtils.writeBlockState(state)
            val blockBaos = ByteArrayOutputStream()
            NbtIo.write(tag, DataOutputStream(blockBaos))
            val arr = blockBaos.toByteArray()
            dos.writeInt(arr.size)
            dos.write(arr)
        }

        // machines not currently implemented
        dos.writeInt(0)

        dos.flush()
        return baos.toByteArray()
    }

    fun deserialize(uuid: UUID, bytes: ByteArray, world: ServerLevel): Starship {
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val registryAccess = world.registryAccess()
        val blockRegistry = registryAccess.lookup(Registries.BLOCK).orElseThrow()

        val schemaVersion = dis.readShort()
        check(schemaVersion == STARSHIPS_VERSION) {
            "unsupported starship schema v$schemaVersion (code at v$STARSHIPS_VERSION)"
        }

        dis.readLong() // world uuid MSB
        dis.readLong() // world uuid LSB

        val originX = dis.readInt()
        val originY = dis.readInt()
        val originZ = dis.readInt()
        val origin = Vec3i(originX, originY, originZ)

        val yaw = dis.readDouble()

        val blockCount = dis.readInt()
        val blocks = HashMap<Vec3i, BlockState>(blockCount)

        repeat(blockCount) {
            val relLong = dis.readLong()
            val relPos = BlockPos.of(relLong)
            val absVec = Vec3i(origin.x + relPos.x, origin.y + relPos.y, origin.z + relPos.z)

            val nbtLen = dis.readInt()
            val nbtBytes = ByteArray(nbtLen)
            dis.readFully(nbtBytes)
            val tag = NbtIo.read(DataInputStream(ByteArrayInputStream(nbtBytes)))
            val blockState = NbtUtils.readBlockState(blockRegistry, tag)

            blocks[absVec] = blockState
        }

        // machines not currently implemented
        val machineRefCount = dis.readInt()
        repeat(machineRefCount) { dis.readLong() }

        return Starship().load(uuid, world, origin, yaw, blocks)
    }

    /** Peeks at the blob header to check if this starship's origin falls in the given chunk. */
    fun matchesChunk(bytes: ByteArray, world: ServerLevel, chunkX: Int, chunkZ: Int): Boolean {
        return try {
            val dis = DataInputStream(ByteArrayInputStream(bytes))
            dis.readShort() // schema version
            dis.readByte()  // ship state
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
