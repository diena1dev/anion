package dev.diena.anion.features.starship

import dev.diena.anion.extensions.blockPos
import net.minecraft.core.Vec3i
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.ticks.LevelChunkTicks
import net.minecraft.world.ticks.ScheduledTick

// presently unused class that helps update pending block update ticks on starships. unreliable, needs a rework.
object StarshipTicks {

	fun transferTicks(level: ServerLevel, oldPositions: Set<Vec3i>, vectorToMoveIn: Vec3i) {

		val gameTime = level.gameTime

		val blockSnapshots = mutableListOf<ScheduledTick<Block>>()

		for (vec in oldPositions) {

			val blockPos = vec.blockPos
			val chunk = level.getChunkIfLoaded(blockPos.x shr 4, blockPos.z shr 4) ?: continue

			val chunkBlockTicks = chunk.blockTicks as LevelChunkTicks<Block>
			chunkBlockTicks.getAll().toList().filter { it.pos() == blockPos }.forEach { blockSnapshots.add(it) }

		}

		for (tick in blockSnapshots) {

			val chunk = level.getChunkIfLoaded(tick.pos().x shr 4, tick.pos().z shr 4) ?: continue
			(chunk.blockTicks as LevelChunkTicks<Block>).removeIf { it.pos() == tick.pos() && it.type() == tick.type() }

		}

		val levelBlockTicks = level.blockTicks

		for (tick in blockSnapshots) {

			val newPos = tick.pos().offset(vectorToMoveIn)
			val triggerTick = maxOf(tick.triggerTick(), gameTime + 1)
			levelBlockTicks.schedule(ScheduledTick(tick.type(), newPos, triggerTick, tick.priority(), tick.subTickOrder()))

		}

	}

}
