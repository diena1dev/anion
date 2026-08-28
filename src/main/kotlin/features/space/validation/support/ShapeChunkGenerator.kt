package dev.diena.anion.features.space.validation.support

import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.server.level.WorldGenRegion
import net.minecraft.world.level.LevelHeightAccessor
import net.minecraft.world.level.NoiseColumn
import net.minecraft.world.level.StructureManager
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.BiomeManager
import net.minecraft.world.level.biome.FixedBiomeSource
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkAccess
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.RandomState
import net.minecraft.world.level.levelgen.blending.Blender
import java.util.concurrent.CompletableFuture

/** true when a block should exist at this world position. */
fun interface SpaceShape {

	fun contains(

		blockX: Int,
		blockY: Int,
		blockZ: Int,

	): Boolean

}

/**
 * A chunk generator that fills exactly the cells a [SpaceShape] claims and leaves the rest void.
 * Written as a validation fixture, but it is also the shape the real asteroid generator takes:
 * everything the rings need is a pure predicate over world coordinates.
 */
class ShapeChunkGenerator(

	biome: Holder<Biome>,
	private val shape: SpaceShape,
	private val fill: BlockState = Blocks.STONE.defaultBlockState(),
	private val minY: Int = 0,
	private val genDepth: Int = 256,

) : ChunkGenerator(FixedBiomeSource(biome)) {

	/** never serialised: the stem carrying this is handed to ServerLevel directly. */
	override fun codec(): MapCodec<out ChunkGenerator> =
		throw UnsupportedOperationException("Cannot serialize ShapeChunkGenerator")

	override fun fillFromNoise(

		blender: Blender,
		randomState: RandomState,
		structureManager: StructureManager,
		centerChunk: ChunkAccess,

	): CompletableFuture<ChunkAccess> {

		val chunkPos = centerChunk.pos
		val originX = chunkPos.minBlockX
		val originZ = chunkPos.minBlockZ
		val topY = minY + genDepth
		val cursor = BlockPos.MutableBlockPos()

		for (offsetX in 0 until 16) for (offsetZ in 0 until 16) {

			val blockX = originX + offsetX
			val blockZ = originZ + offsetZ

			for (blockY in minY until topY) {

				if (!shape.contains(blockX, blockY, blockZ)) continue

				cursor.set(blockX, blockY, blockZ)
				centerChunk.setBlockState(cursor, fill, 0)

			}

		}

		return CompletableFuture.completedFuture(centerChunk)

	}

	override fun buildSurface(

		level: WorldGenRegion,
		structureManager: StructureManager,
		randomState: RandomState,
		protoChunk: ChunkAccess,

	) {
		// NO-OP, the shape is the whole terrain
	}

	override fun applyCarvers(

		region: WorldGenRegion,
		seed: Long,
		randomState: RandomState,
		biomeManager: BiomeManager,
		structureManager: StructureManager,
		chunk: ChunkAccess,

	) {
		// NO-OP, nothing to carve out of vacuum
	}

	override fun spawnOriginalMobs(worldGenRegion: WorldGenRegion) {
		// NO-OP
	}

	override fun addDebugScreenInfo(

		result: MutableList<String>,
		randomState: RandomState,
		feetPos: BlockPos,

	) {

		result.add("Anion shape generator")

	}

	override fun getGenDepth(): Int = genDepth

	override fun getSeaLevel(): Int = minY

	override fun getMinY(): Int = minY

	/** the lowest empty cell above the highest filled one in this column. */
	override fun getBaseHeight(

		blockX: Int,
		blockZ: Int,
		type: Heightmap.Types,
		heightAccessor: LevelHeightAccessor,
		randomState: RandomState,

	): Int {

		for (blockY in (minY + genDepth - 1) downTo minY) {
			if (shape.contains(blockX, blockY, blockZ)) return blockY + 1
		}

		return minY

	}

	override fun getBaseColumn(

		blockX: Int,
		blockZ: Int,
		heightAccessor: LevelHeightAccessor,
		randomState: RandomState,

	): NoiseColumn {

		val air = Blocks.AIR.defaultBlockState()
		val column = Array(genDepth) { index ->
			if (shape.contains(blockX, minY + index, blockZ)) fill else air
		}

		return NoiseColumn(minY, column)

	}

}
