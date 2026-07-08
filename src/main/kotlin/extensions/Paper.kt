@file:Suppress("NOTHING_TO_INLINE", "Unused")

package dev.diena.anion.extensions

import dev.diena.anion.data.registry.AnionRegistryKey
import dev.diena.anion.data.registry.registries.AnionRegistries
import dev.diena.anion.features.custom.items.AnionItem
import io.papermc.paper.datacomponent.DataComponentType
import io.papermc.paper.datacomponent.DataComponentTypes
import net.minecraft.core.BlockPos
import net.kyori.adventure.key.Key
import net.minecraft.core.BlockPos.MutableBlockPos
import net.minecraft.core.SectionPos
import net.minecraft.core.Vec3i
import net.minecraft.world.phys.Vec3
import org.bukkit.Location
import org.bukkit.RegionAccessor
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Entity
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector
import org.jetbrains.annotations.ApiStatus.Experimental
import org.joml.Quaternionf

inline fun <reified E: Entity> RegionAccessor.spawn(
    location: Location,
    reason: SpawnReason = CUSTOM,
    noinline function: (entity: E) -> Unit,
): E = spawn<E>(location, E::class.java, function, reason)

inline fun <reified E: Entity> Location.spawn(
    reason: SpawnReason = CUSTOM,
    noinline function: (entity: E) -> Unit,
): E = world.spawn(this, reason, function)

@Suppress("UnstableApiUsage")
fun ItemStack.toAnionItem(): AnionItem? {
    val model: Key = getData(DataComponentTypes.ITEM_MODEL) ?: return null
    return AnionRegistries.ITEM_REGISTRY.getValue(AnionRegistryKey(model.value()))
}

@Experimental
inline operator fun <T : Any> ItemStack.set(
    @Suppress("UnstableApiUsage") component: DataComponentType.Valued<T>,
    value: T
) = @Suppress("UnstableApiUsage") setData(component, value)

@Experimental
inline operator fun<T : Any> ItemStack.get(
    @Suppress("UnstableApiUsage") component: DataComponentType.Valued<T>,
): T? = @Suppress("UnstableApiUsage") getData(component)

inline operator fun Location.plus(other: Vector) = add(other)
inline operator fun Location.plusAssign(other: Vector) { add(other) }

inline fun Location.toBlockPos() = BlockPos(blockX, blockY, blockZ)

inline operator fun Vector.unaryPlus() = Vector(x, y, z)
inline operator fun Vector.unaryMinus() = Vector(-x, -y, -z)
inline operator fun Vector.plus(other: Vector) = Vector(x + other.x, y + other.y, z + other.z)
inline operator fun Vector.plus(other: Int) = Vector(x + other, y + other, z + other)
inline operator fun Vector.plus(other: Long) = Vector(x + other, y + other, z + other)
inline operator fun Vector.plus(other: Float) = Vector(x + other, y + other, z + other)
inline operator fun Vector.plus(other: Double) = Vector(x + other, y + other, z + other)
inline operator fun Vector.minus(other: Vector) = Vector(x - other.x, y - other.y, z - other.z)
inline operator fun Vector.minus(other: Int) = Vector(x - other, y - other, z - other)
inline operator fun Vector.minus(other: Long) = Vector(x - other, y - other, z - other)
inline operator fun Vector.minus(other: Float) = Vector(x - other, y - other, z - other)
inline operator fun Vector.minus(other: Double) = Vector(x - other, y - other, z - other)
inline operator fun Vector.times(other: Vector) = Vector(x * other.x, y * other.y, z * other.z)
inline operator fun Vector.times(other: Int) = Vector(x * other, y * other, z * other)
inline operator fun Vector.times(other: Long) = Vector(x * other, y * other, z * other)
inline operator fun Vector.times(other: Float) = Vector(x * other, y * other, z * other)
inline operator fun Vector.times(other: Double) = Vector(x * other, y * other, z * other)
inline operator fun Vector.div(other: Vector) = Vector(x / other.x, y / other.y, z / other.z)
inline operator fun Vector.div(other: Int) = Vector(x / other, y / other, z / other)
inline operator fun Vector.div(other: Float) = Vector(x / other, y / other, z / other)
inline operator fun Vector.div(other: Double) = Vector(x / other, y / other, z / other)
inline operator fun Vector.plusAssign(other: Vector) { add(other) }
inline operator fun Vector.minusAssign(other: Vector) { subtract(other) }
inline operator fun Vector.timesAssign(other: Vector) { multiply(other) }
inline operator fun Vector.timesAssign(other: Int) { multiply(other) }
inline operator fun Vector.timesAssign(other: Float) { multiply(other) }
inline operator fun Vector.timesAssign(other: Double) { multiply(other) }
inline operator fun Vector.divAssign(other: Vector) { divide(other) }

inline fun Quaternionf.times(other: Quaternionf): Quaternionf {
    return Quaternionf(
        w * other.x + x * other.w + y * other.z - z * other.y,  // x
        w * other.y - x * other.z + y * other.w + z * other.x,  // y
        w * other.z + x * other.y - y * other.x + z * other.w,  // z
        w * other.w - x * other.x - y * other.y - z * other.z,  // real number
    )
}

inline operator fun BlockPos.unaryPlus() = this
inline operator fun BlockPos.unaryMinus() = BlockPos(-x, -y, -z)
inline operator fun BlockPos.plus(other: Vec3i): BlockPos = offset(other)
inline operator fun BlockPos.minus(other: Vec3i): BlockPos = offset(-other.x, -other.y, -other.z)
inline operator fun BlockPos.times(other: Vec3i): Vec3i = multiply(other.x, other.y, other.z)
inline operator fun BlockPos.times(other: Int): BlockPos = multiply(other)
inline operator fun BlockPos.div(other: Int): BlockPos = BlockPos(x / other, y / other, z / other)

inline operator fun SectionPos.unaryPlus() = this
inline operator fun SectionPos.unaryMinus() = SectionPos.of(-x, -y, -z)
inline operator fun SectionPos.plus(other: Vec3i): SectionPos = offset(other.x, other.y, other.z)
inline operator fun SectionPos.minus(other: Vec3i): SectionPos = offset(-other.x, -other.y, -other.z)

inline operator fun MutableBlockPos.plusAssign(other: Vec3i) { move(other) }
inline operator fun MutableBlockPos.minusAssign(other: Vec3i) { move(-other.x, -other.y, -other.z) }

inline operator fun Vec3.unaryPlus() = this
inline operator fun Vec3.unaryMinus() = Vector(-x, -y, -z)
inline operator fun Vec3.plus(other: Vec3) = add(other)
inline operator fun Vec3.minus(other: Vec3) = subtract(other)
inline operator fun Vec3.times(other: Vec3) = multiply(other)
inline operator fun Vec3.div(other: Int) = Vec3(x / other, y / other, z / other)

inline operator fun Vec3i.unaryPlus() = this
inline operator fun Vec3i.unaryMinus() = Vector(-x, -y, -z)
inline operator fun Vec3i.plus(other: Vec3i) = Vec3i(x + other.x, y + other.y, z + other.z)
inline operator fun Vec3i.minus(other: Vec3i) = Vec3i(x - other.x, y - other.y, z - other.z)
inline operator fun Vec3i.times(other: Vec3i) = Vec3i(x * other.x, y * other.y, z * other.z)
inline operator fun Vec3i.times(other: Int) = Vec3i(x * other, y * other, z * other)
inline operator fun Vec3i.div(other: Vec3i) = Vec3i(x / other.x, y / other.y, z / other.z)
inline operator fun Vec3i.div(other: Int) = Vec3i(x / other, y / other, z / other)

fun BlockFace.rotateRight() = when (this) {
    BlockFace.NORTH -> BlockFace.EAST
    BlockFace.EAST -> BlockFace.SOUTH
    BlockFace.SOUTH -> BlockFace.WEST
    BlockFace.WEST -> BlockFace.NORTH
    else -> throw NotImplementedError("non-cartesian faces are not supported")
}

fun BlockFace.rotateLeft() = when (this) {
    BlockFace.NORTH -> BlockFace.WEST
    BlockFace.WEST -> BlockFace.SOUTH
    BlockFace.SOUTH -> BlockFace.EAST
    BlockFace.EAST -> BlockFace.NORTH
    else -> throw NotImplementedError("non-cartesian faces are not supported")
}

inline val Block.blockPos get() = BlockPos(x, y, z)
inline val Block.vec3i get() = Vec3i(x, y, z)
inline val Block.adjacentBlocks get() = run {
	val blockSet: MutableSet<Block> = mutableSetOf()
	blockSet.add(world.getBlockAt(x+1, y, z))
	blockSet.add(world.getBlockAt(x-1, y, z))
	blockSet.add(world.getBlockAt(x, y+1, z))
	blockSet.add(world.getBlockAt(x, y-1, z))
	blockSet.add(world.getBlockAt(x, y, z+1))
	blockSet.add(world.getBlockAt(x, y, z-1))

    blockSet
}