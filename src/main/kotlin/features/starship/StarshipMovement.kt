package dev.diena.anion.features.starship

import dev.diena.anion.extensions.blockPos
import dev.diena.anion.extensions.minus
import dev.diena.anion.extensions.plus
import dev.diena.anion.extensions.rotateWithAnion
import net.minecraft.core.Vec3i
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.entity.PositionMoveRotation
import net.minecraft.world.entity.Relative
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Player
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator
import kotlin.collections.set

object StarshipMovement {

	// internal airBLock reference
	// TODO: REPLACE MOVEMENT WITH CURSED FAWE MUTATION METHODS
	private val airBlock = Blocks.AIR.defaultBlockState()

	fun move(

		vectorToMoveIn: Vec3i,
		starship: Starship,

	) : ConcurrentHashMap<Vec3i, BlockState> {

		val blockEntityBlockMap = moveBlockEntities(vectorToMoveIn, starship)
		val newBlockMap = moveBlocks(vectorToMoveIn, starship, blockEntityBlockMap)
		moveEntities(vectorToMoveIn, starship)

		return newBlockMap

	}

	/** rewrites the world for a rotation of [rotationSteps] clockwise quarter turns, 1..3.
	 *  yaw is owned by [Starship.rotate], which resolves the step count and skips this entirely at 0. */
	fun rotate(

		rotationSteps: Int,
		starship: Starship,

	) : ConcurrentHashMap<Vec3i, BlockState> {

		val blockEntityBlockMap = rotateBlockEntities(rotationSteps, starship)
		val newBlockMap = rotateBlocks(rotationSteps, starship, blockEntityBlockMap)
		rotateEntities(rotationSteps, starship)

		return newBlockMap

	}

	///////////
	// MOVEMENT
	///////////

	private fun moveBlocks(

		vectorToMoveIn: Vec3i,
		starship: Starship,
		blockEntityBlockMap: HashMap<Vec3i, BlockState>,

	) : ConcurrentHashMap<Vec3i, BlockState> {

		val newBlockMap: ConcurrentHashMap<Vec3i, BlockState> = ConcurrentHashMap() // nms BlockState collection

		// translate blocks
		for ((vec, _) in starship.blockHashMap) {

			newBlockMap[vec + vectorToMoveIn] = starship.blockHashMap[vec] ?: continue

		}

		// remove old ship section
		for ((vec, _) in starship.blockHashMap) {

			if (vec in blockEntityBlockMap) continue

			// 4. no observer updates, 16. no shape recalc, 32. no item drops
			starship.level.setBlock(vec.blockPos, airBlock, 4 or 16 or 32)

		}

		// move ship blocks
		for ((vec, blockState) in newBlockMap) {

			if (vec in blockEntityBlockMap) continue

			// 1. update neighboring blocks, 4. no observer updates, 16. no shape recalc
			starship.level.setBlock(vec.blockPos, blockState, 1 or 4 or 16)

		}

		// TODO: DO NOT DO THIS, PACKETS MUST BE HANDLED IN STARSHIP
		StarshipPackets.sendSections(starship.level, newBlockMap.keys, starship.blockHashMap.keys, newBlockMap)

		return newBlockMap

	}

	/** call this method before [moveBlocks]! */
	private fun moveBlockEntities(

		vectorToMoveIn: Vec3i,
		starship: Starship,

	) : HashMap<Vec3i, BlockState> {

		val blockEntityNbtMap: HashMap<Vec3i, CompoundTag>   = hashMapOf()
		val blockEntityBlockMap: HashMap<Vec3i, BlockState> = hashMapOf()
		val provider                                        = starship.level.registryAccess()

		for ((vec, _) in starship.blockHashMap) {

			// store BEs and remove BE blocks
			val blockEntity = starship.level.getBlockEntity(vec.blockPos) ?: continue
			val nbt = blockEntity.saveWithFullMetadata(provider)
			val newPos = vec + vectorToMoveIn

			nbt.putInt("x", newPos.x)
			nbt.putInt("y", newPos.y)
			nbt.putInt("z", newPos.z)

			blockEntityNbtMap[newPos] = nbt
			blockEntityBlockMap[newPos] = starship.level.getBlockState(vec.blockPos)
			starship.level.removeBlockEntity(vec.blockPos)

		}

		// then load ship BEs (setting blocks in the process)
		for ((vec, nbt) in blockEntityNbtMap) {

			val blockState = blockEntityBlockMap[vec] ?: continue
			starship.level.setBlock(vec.blockPos, blockState, 4 or 16 or 32)

			val newBlockEntity = BlockEntity.loadStatic(vec.blockPos, blockState, nbt, provider) ?: continue
			starship.level.setBlockEntity(newBlockEntity)

		}

		return blockEntityBlockMap

	}

	/** Handle riding entities within the bounding box of the starship and touching starship blocks. */
	private fun moveEntities(

		vectorToMoveIn: Vec3i,
		starship: Starship,

	) {

		// FIXME: incorrect teleport handling?
		// and teleport the player after all that fun is done
		val newX = vectorToMoveIn.x.toDouble()
		val newY = vectorToMoveIn.y.toDouble()
		val newZ = vectorToMoveIn.z.toDouble()

		// acceptable use of another class, since it's going through the Starship class
		for (entity in starship.hitbox.getEntitiesWithin()) {

			val bukkitEntity = entity.bukkitEntity
			if (bukkitEntity is Player) {
				// I FUCKING HATE NMS
				val destination = PositionMoveRotation(Vec3(newX, newY, newZ), Vec3.ZERO, 0f, 0f)

				(bukkitEntity as CraftPlayer).handle.connection.teleport(
					destination,
					setOf(Relative.X, Relative.Y, Relative.Z, Relative.Y_ROT, Relative.X_ROT)
				)

			} else {

				bukkitEntity.teleport(bukkitEntity.location.add(newX, newY, newZ))

			}

		}

	}

	///////////
	// ROTATION
	///////////

	// ROTATION HELPERS START

	/** the ship yaw whose face points the same way as vanilla entity yaw [entityYaw]. */
	fun shipYawFacing(entityYaw: Float): Double {

		// ship yaw runs the opposite way round from entity yaw (S, E, N, W against S, W, N, E), and its faces
		// are bucketed from 0 upward, so the +45 lands an entity looking straight down a face mid-bucket.
		return ((45.0 - entityYaw) % 360 + 360) % 360

	}

	/** the shortest relative rotation, in degrees, that turns [starship] to [targetYaw]. */
	fun yawDelta(starship: Starship, targetYaw: Double): Double {

		return ((targetYaw - starship.yaw) % 360 + 540) % 360 - 180

	}

	private fun rotateVec(relative: Vec3i, steps: Int): Vec3i {

		var rotatedX = relative.x; var rotatedZ = relative.z
		repeat(steps) { val nextX = -rotatedZ; rotatedZ = rotatedX; rotatedX = nextX }
		return Vec3i(rotatedX, relative.y, rotatedZ)

	}

	// ROTATION HELPERS END

	private fun rotateBlocks(

		rotationSteps: Int,
		starship: Starship,
		blockEntityBlockMap: HashMap<Vec3i, BlockState>,

	) : ConcurrentHashMap<Vec3i, BlockState> {

		val nmsRotation = when (rotationSteps) {
			1    -> Rotation.CLOCKWISE_90
			2    -> Rotation.CLOCKWISE_180
			3    -> Rotation.COUNTERCLOCKWISE_90
			else -> throw IllegalStateException("what the fuck did you do")
		}

		val newBlockMap: ConcurrentHashMap<Vec3i, BlockState> = ConcurrentHashMap()

		// translate blocks
		for ((vec, state) in starship.blockHashMap) {

			val newVec = starship.origin + rotateVec(vec - starship.origin, rotationSteps)
			newBlockMap[newVec] = state.rotateWithAnion(nmsRotation)

		}

		// remove old ship section
		for ((vec, _) in starship.blockHashMap) {

			if (vec in blockEntityBlockMap) continue

			// 4. no observer updates, 16. no shape recalc, 32. no item drops
			starship.level.setBlock(vec.blockPos, airBlock, 4 or 16 or 32)

		}

		// move ship blocks
		for ((vec, blockState) in newBlockMap) {

			if (vec in blockEntityBlockMap) continue

			// 1. update neighboring blocks, 4. no observer updates, 16. no shape recalc
			starship.level.setBlock(vec.blockPos, blockState, 1 or 4 or 16)

		}

		// TODO: DO NOT DO THIS, PACKETS MUST BE HANDLED IN STARSHIP
		StarshipPackets.sendSections(starship.level, newBlockMap.keys, starship.blockHashMap.keys, newBlockMap)

		return newBlockMap

	}

	/** call this method before [rotateBlocks]! */
	fun rotateBlockEntities(

		rotationSteps: Int,
		starship: Starship,

	) : HashMap<Vec3i, BlockState> {

		val blockEntityNbtMap: HashMap<Vec3i, CompoundTag>   = hashMapOf()
		val blockEntityBlockMap: HashMap<Vec3i, BlockState> = hashMapOf()
		val provider                                        = starship.level.registryAccess()

		for ((vec, _) in starship.blockHashMap) {

			// store BEs and remove BE blocks
			val blockEntity = starship.level.getBlockEntity(vec.blockPos) ?: continue
			val nbt = blockEntity.saveWithFullMetadata(provider)
			val newPos = starship.origin + rotateVec(vec - starship.origin, rotationSteps)

			nbt.putInt("x", newPos.x)
			nbt.putInt("y", newPos.y)
			nbt.putInt("z", newPos.z)

			blockEntityNbtMap[newPos] = nbt
			blockEntityBlockMap[newPos] = starship.level.getBlockState(vec.blockPos)
			starship.level.removeBlockEntity(vec.blockPos)

		}

		// then load ship BEs (setting blocks in the process)
		for ((vec, nbt) in blockEntityNbtMap) {

			val blockState = blockEntityBlockMap[vec] ?: continue
			starship.level.setBlock(vec.blockPos, blockState, 4 or 16 or 32)

			val newBlockEntity = BlockEntity.loadStatic(vec.blockPos, blockState, nbt, provider) ?: continue
			starship.level.setBlockEntity(newBlockEntity)

		}

		return blockEntityBlockMap

	}

	fun rotateEntities(

		rotationSteps: Int,
		starship: Starship,

	) {

		for (entity in starship.hitbox.getEntitiesWithin()) {

			val entityVec = Vec3i(entity.x.toInt(), entity.y.toInt(), entity.z.toInt())
			val rotatedVec = starship.origin + rotateVec(entityVec - starship.origin, rotationSteps)

			val newX = (rotatedVec.x - entityVec.x).toDouble()
			val newY = (rotatedVec.y - entityVec.y).toDouble()
			val newZ = (rotatedVec.z - entityVec.z).toDouble()

			val bukkitEntity = entity.bukkitEntity
			if (bukkitEntity is Player) {
				// I FUCKING HATE NMS
				val destination = PositionMoveRotation(Vec3(newX, newY, newZ), Vec3.ZERO, 0f, 0f)

				(bukkitEntity as CraftPlayer).handle.connection.teleport(
					destination,
					setOf(Relative.X, Relative.Y, Relative.Z, Relative.Y_ROT, Relative.X_ROT)
				)

			} else {

				bukkitEntity.teleport(bukkitEntity.location.add(newX, newY, newZ))

			}

		}

	}

}
