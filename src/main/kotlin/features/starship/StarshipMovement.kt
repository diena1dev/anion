package dev.diena.anion.features.starship

import dev.diena.anion.extensions.blockPos
import dev.diena.anion.extensions.minus
import dev.diena.anion.extensions.plus
import dev.diena.anion.extensions.rotateRight
import net.minecraft.core.Vec3i
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.entity.PositionMoveRotation
import net.minecraft.world.entity.Relative
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.bukkit.block.BlockFace
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Player
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

	) : HashMap<Vec3i, BlockState> {

		val beBlockMap = moveBlockEntities(vectorToMoveIn, starship)
		val newBlockMap = moveBlocks(vectorToMoveIn, starship, beBlockMap)
		moveEntities(vectorToMoveIn, starship)

		return newBlockMap

	}

	fun rotate(

		byAngle: Float,
		starship: Starship,

	) : HashMap<Vec3i, BlockState> {

		val oldYaw = starship.yaw                                                  // alias to starship's current yaw
		starship.yaw = ((oldYaw + byAngle % 360) + 360) % 360                      // modulo to wraparound whatever angle we get
		if (oldYaw.toFace() == starship.yaw.toFace()) return starship.blockHashMap // do not rotate if yaw difference was not large enough

		val steps = stepsFromTo(oldYaw.toFace(), starship.yaw.toFace())

		val beBlockMap = rotateBlockEntities(steps, starship)
		val newBlockMap = rotateBlocks(steps, starship, beBlockMap)
		rotateEntities(steps, starship)

		return newBlockMap

	}

	///////////
	// MOVEMENT
	///////////

	private fun moveBlocks(

		vectorToMoveIn: Vec3i,
		starship: Starship,
		beBlockMap: HashMap<Vec3i, BlockState>,

	) : HashMap<Vec3i, BlockState> {

		val newBlockMap: HashMap<Vec3i, BlockState> = hashMapOf() // nms BlockState collection

		// translate blocks
		for ((vec, _) in starship.blockHashMap) {

			newBlockMap[vec + vectorToMoveIn] = starship.blockHashMap[vec] ?: continue

		}

		// remove old ship section
		for ((vec, _) in starship.blockHashMap) {

			if (vec in beBlockMap) continue

			// 4. no observer updates, 16. no shape recalc, 32. no item drops
			starship.level.setBlock(vec.blockPos, airBlock, 4 or 16 or 32)

		}

		// move ship blocks
		for ((vec, b) in newBlockMap) {

			if (vec in beBlockMap) continue

			// 1. update neighboring blocks, 4. no observer updates, 16. no shape recalc
			starship.level.setBlock(vec.blockPos, b, 1 or 4 or 16)

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

		val beMap: HashMap<Vec3i, CompoundTag>     = hashMapOf()
		val beBlockMap: HashMap<Vec3i, BlockState> = hashMapOf()
		val provider                               = starship.level.registryAccess()

		for ((vec, _) in starship.blockHashMap) {

			// store BEs and remove BE blocks
			val be = starship.level.getBlockEntity(vec.blockPos) ?: continue
			val nbt = be.saveWithFullMetadata(provider)
			val newPos = vec + vectorToMoveIn

			nbt.putInt("x", newPos.x)
			nbt.putInt("y", newPos.y)
			nbt.putInt("z", newPos.z)

			beMap[newPos] = nbt
			beBlockMap[newPos] = starship.level.getBlockState(vec.blockPos)
			starship.level.removeBlockEntity(vec.blockPos)

		}

		// then load ship BEs (setting blocks in the process)
		for ((vec, nbt) in beMap) {

			val bs = beBlockMap[vec] ?: continue
			starship.level.setBlock(vec.blockPos, bs, 4 or 16 or 32)

			val newBe = BlockEntity.loadStatic(vec.blockPos, bs, nbt, provider) ?: continue
			starship.level.setBlockEntity(newBe)

		}

		return beBlockMap

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
		for (entity in starship.hitbox.getEntitiesWithin(starship)) {

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

	private fun Double.toFace(): BlockFace = when (this) {

		in 0.0..90.0 -> BlockFace.SOUTH
		in 90.0..180.0 -> BlockFace.EAST
		in 180.0..270.0 -> BlockFace.NORTH
		in 270.0..360.0 -> BlockFace.WEST
		else -> throw IllegalStateException("what the fuck did you do")

	}

	private fun stepsFromTo(from: BlockFace, to: BlockFace): Int {

		var steps = 0; var cur = from
		while (cur != to && steps < 4) { cur = cur.rotateRight(); steps++ }
		return steps

	}

	private fun rotateVec(rel: Vec3i, steps: Int): Vec3i {

		var x = rel.x; var z = rel.z
		repeat(steps) { val nx = -z; z = x; x = nx }
		return Vec3i(x, rel.y, z)

	}

	// ROTATION HELPERS END

	private fun rotateBlocks(

		rotationSteps: Int,
		starship: Starship,
		beBlockMap: HashMap<Vec3i, BlockState>,

	) : HashMap<Vec3i, BlockState> {

		val nmsRotation = when (rotationSteps) {
			1    -> Rotation.CLOCKWISE_90
			2    -> Rotation.CLOCKWISE_180
			3    -> Rotation.COUNTERCLOCKWISE_90
			else -> throw IllegalStateException("what the fuck did you do")
		}

		val newBlockMap: HashMap<Vec3i, BlockState> = hashMapOf()

		// translate blocks
		for ((vec, state) in starship.blockHashMap) {

			val newVec = starship.origin + rotateVec(vec - starship.origin, rotationSteps)
			newBlockMap[newVec] = state.rotate(nmsRotation)

		}

		// remove old ship section
		for ((vec, _) in starship.blockHashMap) {

			if (vec in beBlockMap) continue

			// 4. no observer updates, 16. no shape recalc, 32. no item drops
			starship.level.setBlock(vec.blockPos, airBlock, 4 or 16 or 32)

		}

		// move ship blocks
		for ((vec, b) in newBlockMap) {

			if (vec in beBlockMap) continue

			// 1. update neighboring blocks, 4. no observer updates, 16. no shape recalc
			starship.level.setBlock(vec.blockPos, b, 1 or 4 or 16)

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

		val beMap: HashMap<Vec3i, CompoundTag>     = hashMapOf()
		val beBlockMap: HashMap<Vec3i, BlockState> = hashMapOf()
		val provider                               = starship.level.registryAccess()

		for ((vec, _) in starship.blockHashMap) {

			// store BEs and remove BE blocks
			val be = starship.level.getBlockEntity(vec.blockPos) ?: continue
			val nbt = be.saveWithFullMetadata(provider)
			val newPos = starship.origin + rotateVec(vec - starship.origin, rotationSteps)

			nbt.putInt("x", newPos.x)
			nbt.putInt("y", newPos.y)
			nbt.putInt("z", newPos.z)

			beMap[newPos] = nbt
			beBlockMap[newPos] = starship.level.getBlockState(vec.blockPos)
			starship.level.removeBlockEntity(vec.blockPos)

		}

		// then load ship BEs (setting blocks in the process)
		for ((vec, nbt) in beMap) {

			val bs = beBlockMap[vec] ?: continue
			starship.level.setBlock(vec.blockPos, bs, 4 or 16 or 32)

			val newBe = BlockEntity.loadStatic(vec.blockPos, bs, nbt, provider) ?: continue
			starship.level.setBlockEntity(newBe)

		}

		return beBlockMap

	}

	fun rotateEntities(

		rotationSteps: Int,
		starship: Starship,

	) {

		for (entity in starship.hitbox.getEntitiesWithin(starship)) {

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
