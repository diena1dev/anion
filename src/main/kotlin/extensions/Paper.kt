@file:Suppress("NOTHING_TO_INLINE", "Unused")

package dev.diena.anion.extensions

import dev.diena.anion.data.registry.AnionRegistryKey
import dev.diena.anion.data.registry.registries.AnionRegistries
import dev.diena.anion.features.custom.ItemKey
import dev.diena.anion.features.custom.blocks.AnionBlock
import dev.diena.anion.features.custom.blocks.AnionBlocks
import dev.diena.anion.features.custom.blocks.AnionPillarBlock
import dev.diena.anion.features.custom.items.AnionItem
import io.papermc.paper.datacomponent.DataComponentType
import io.papermc.paper.datacomponent.DataComponentTypes
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.TextColor
import net.minecraft.core.BlockPos
import net.minecraft.core.BlockPos.MutableBlockPos
import net.minecraft.core.SectionPos
import net.minecraft.core.Vec3i
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import net.minecraft.world.level.block.NoteBlock as NmsNoteBlock
import org.bukkit.Axis
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.RegionAccessor
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.NoteBlock
import org.bukkit.craftbukkit.block.data.CraftBlockData
import org.bukkit.entity.Entity
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM
import org.bukkit.inventory.Inventory
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

inline val Vec3i.blockPos get() = BlockPos(x, y, z)
inline val Vec3.vec3i get() = Vec3i(x.toInt(), y.toInt(), z.toInt())

/** [vec3i] truncates toward zero, so -0.5 and 0.5 both land on 0 and negative motion is biased upward.
 *  this rounds consistently downward, which is what block-lattice coordinates want. */
inline val Vec3.floorVec3i get() = Vec3i(
	kotlin.math.floor(x).toInt(),
	kotlin.math.floor(y).toInt(),
	kotlin.math.floor(z).toInt(),
)

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

/** Quantises a yaw in degrees down to the cardinal face it currently reads as. */
// NOTE: the bounds overlap, so an exact 90/180/270 resolves to the lower face. preserved from the
// three private copies this replaced — do not "fix" it without checking the rotation path still lines up.
fun Double.toFace(): BlockFace = when (this) {

	in 0.0..90.0 -> BlockFace.SOUTH
	in 90.0..180.0 -> BlockFace.EAST
	in 180.0..270.0 -> BlockFace.NORTH
	in 270.0..360.0 -> BlockFace.WEST
	else -> throw IllegalStateException("what the fuck did you do")

}

/** Clockwise quarter turns needed to get [from] onto [to]. 0 when they already match. */
fun stepsFromTo(from: BlockFace, to: BlockFace): Int {

	var steps = 0; var currentFace = from
	while (currentFace != to && steps < 4) { currentFace = currentFace.rotateRight(); steps++ }
	return steps

}

/** Rotate given vector by 90 degrees. */
inline fun Vec3i.rotate(rotation: Rotation): Vec3i =
	when (rotation) {
		Rotation.CLOCKWISE_90 -> Vec3i(-this.z, this.y, this.x)
		Rotation.CLOCKWISE_180 -> Vec3i(-this.x, this.y, -this.z)
		Rotation.COUNTERCLOCKWISE_90 -> Vec3i(this.z, this.y, -this.x)
		Rotation.NONE -> this
	}

/** Rotate [point] around [origin] by [rotation]. */
fun Vec3i.rotateAround(origin: Vec3i, rotation: Rotation): Vec3i {
	val relative = this - origin
	return origin + relative.rotate(rotation)
}

/** Clockwise quarter turns this [Rotation] represents, 0..3. */
val Rotation.quarterTurns: Int get() = when (this) {
	Rotation.NONE -> 0
	Rotation.CLOCKWISE_90 -> 1
	Rotation.CLOCKWISE_180 -> 2
	Rotation.COUNTERCLOCKWISE_90 -> 3
}

/** [Rotation] for a count of clockwise quarter turns. Wraps, negatives included. */
fun rotationOf(quarterTurns: Int): Rotation = when (((quarterTurns % 4) + 4) % 4) {
	0 -> Rotation.NONE
	1 -> Rotation.CLOCKWISE_90
	2 -> Rotation.CLOCKWISE_180
	else -> Rotation.COUNTERCLOCKWISE_90
}

/** Compose two rotations. Rotations about the same axis commute, so order is irrelevant. */
operator fun Rotation.plus(other: Rotation): Rotation = rotationOf(this.quarterTurns + other.quarterTurns)

/** Rotate [Location] around [origin] by [rotation]. World/yaw/pitch preserved. */
fun Location.rotateAround(origin: Vec3i, rotation: Rotation): Location {

	val relativeX = x - origin.x
	val relativeZ = z - origin.z

	val (rotatedX, rotatedZ) = when (rotation) {
		Rotation.CLOCKWISE_90 -> -relativeZ to relativeX
		Rotation.CLOCKWISE_180 -> -relativeX to -relativeZ
		Rotation.COUNTERCLOCKWISE_90 -> relativeZ to -relativeX
		Rotation.NONE -> relativeX to relativeZ
	}

	return Location(world, origin.x + rotatedX, y, origin.z + rotatedZ, yaw, pitch)

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

inline val Location.blockPos get() = BlockPos(x.toInt(), y.toInt(), z.toInt())

/** Colors each character of this component's content as a gradient between [from] and [to]. Children are kept as-is. */
fun TextComponent.gradient(from: TextColor, to: TextColor): TextComponent {
	val text = content()
	if (text.isEmpty()) return this

	val lastIndex = text.length - 1
	val builder = Component.text().style(style())
	for (i in text.indices) {
		val fraction = if (lastIndex == 0) 0f else i / lastIndex.toFloat()
		builder.append(Component.text(text[i], TextColor.lerp(fraction, from, to)))
	}
	builder.append(children())

	return builder.build()
}

//////////////////////
///// VANILLA INVENTORY
//////////////////////

// item moves are matched with ItemStack.isSimilar, which compares data components but not amount —
// so a damaged tool never merges with a pristine one. this is the whole of the item movement the
// transport system will own; buffers never touch an Inventory themselves.

/** Units of [key]'s exact variant held across every slot. */
fun Inventory.countOf(key: ItemKey): Long {

	var total = 0L

	for (slot in 0 until size) {
		val stack = getItem(slot) ?: continue
		if (stack.isSimilar(key.stack)) total += stack.amount
	}

	return total

}

/** Takes up to [units] of [key] out. Returns how many were actually taken. */
fun Inventory.drawItem(key: ItemKey, units: Long): Long {

	if (units <= 0L) return 0L

	var remaining = units

	for (slot in 0 until size) {

		if (remaining <= 0L) break

		val stack = getItem(slot) ?: continue
		if (!stack.isSimilar(key.stack)) continue

		val taken = minOf(remaining, stack.amount.toLong()).toInt()

		if (taken == stack.amount) setItem(slot, null)
		else setItem(slot, stack.asQuantity(stack.amount - taken))

		remaining -= taken

	}

	return units - remaining

}

/** Whether [key] would fit anywhere: an empty slot, or on top of a stack that is not yet full. */
fun Inventory.hasRoomFor(key: ItemKey): Boolean {

	for (slot in 0 until size) {
		val stack = getItem(slot)

		if (stack == null || stack.type.isAir) return true
		if (stack.isSimilar(key.stack) && stack.amount < stack.maxStackSize) return true
	}

	return false

}

/** Every distinct item variant held, one entry per variant no matter how many slots it spans. */
fun Inventory.itemKeys(): List<ItemKey> =
	contents
		.filterNotNull()
		.filterNot { it.type.isAir }
		.map { ItemKey.of(it) }
		.distinct()

/** Puts up to [units] of [key] in. Returns how many actually landed. */
// addItem does the partial-stack merging and stack-size clamping, and hands back whatever did not fit
fun Inventory.pushItem(key: ItemKey, units: Long): Long {

	if (units <= 0L) return 0L

	val stackSize = key.stack.maxStackSize.coerceAtLeast(1).toLong()
	var remaining = units
	var placed = 0L

	while (remaining > 0L) {

		val batch = minOf(remaining, stackSize).toInt()
		val leftover = addItem(key.asItemStack(batch)).values.sumOf { it.amount }

		placed += batch - leftover
		remaining -= batch

		if (leftover > 0) break // inventory is full, further batches would only fail the same way

	}

	return placed

}

///////////////////
///// ANION BLOCKS
///////////////////

/** the six cartesian faces, in the order everything that walks a grid should use */
val CARTESIAN_FACES = listOf(
	BlockFace.NORTH,
	BlockFace.EAST,
	BlockFace.SOUTH,
	BlockFace.WEST,
	BlockFace.UP,
	BlockFace.DOWN,
)

inline val BlockFace.vec3i get() = Vec3i(modX, modY, modZ)

/** The axis this face lies on, or null for a non-cartesian one. */
val BlockFace.axis: Axis?
	get() = when (this) {

		BlockFace.NORTH, BlockFace.SOUTH -> Axis.Z
		BlockFace.EAST,  BlockFace.WEST  -> Axis.X
		BlockFace.UP,    BlockFace.DOWN  -> Axis.Y

		else -> null

	}

/** The two faces at the ends of this axis. */
val Axis.faces: List<BlockFace>
	get() = when (this) {

		Axis.X -> listOf(BlockFace.EAST, BlockFace.WEST)
		Axis.Y -> listOf(BlockFace.UP, BlockFace.DOWN)
		Axis.Z -> listOf(BlockFace.SOUTH, BlockFace.NORTH)

	}

/** This axis after [rotation]. A quarter turn swaps the horizontal pair; Y never moves. */
// an axis has no direction, so a half turn leaves every one of them where it was
fun Axis.rotated(rotation: Rotation): Axis = when (rotation) {

	Rotation.CLOCKWISE_90, Rotation.COUNTERCLOCKWISE_90 -> when (this) {

		Axis.X -> Axis.Z
		Axis.Z -> Axis.X
		Axis.Y -> Axis.Y

	}

	else -> this

}

/**
 * Rotates this block state, including the part vanilla cannot do for us.
 *
 * A note block has no rotatable property, and an [AnionPillarBlock] keeps its axis in the note, so
 * plain [BlockState.rotate] leaves a pipe lying the way it was before the ship turned.
 */
fun BlockState.rotateWithAnion(rotation: Rotation): BlockState {

	val rotated = this.rotate(rotation)

	val noteBlock = CraftBlockData.createData(rotated) as? NoteBlock ?: return rotated
	val note = noteBlock.note.id.toInt()

	val anionBlock = AnionBlocks.fromState(noteBlock.instrument, note) ?: return rotated
	val turned = anionBlock.noteAfterRotation(note, rotation)

	if (turned == note) return rotated

	return rotated.setValue(NmsNoteBlock.NOTE, turned)

}

/** The registered AnionBlock this world block encodes, or null if it is not one. */
val Block.anionBlock: AnionBlock?
	get() {
		if (type != Material.NOTE_BLOCK) return null
		val noteBlock = blockData as? NoteBlock ?: return null

		return AnionBlocks.fromState(noteBlock.instrument, noteBlock.note.id.toInt())
	}

/** Which way this block runs, or null when it is not a placed [AnionPillarBlock]. */
val Block.anionAxis: Axis?
	get() {
		val pillar = anionBlock as? AnionPillarBlock ?: return null
		val noteBlock = blockData as? NoteBlock ?: return null

		return pillar.axisOf(noteBlock.note.id.toInt())
	}
