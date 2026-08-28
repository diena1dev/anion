package dev.diena.anion.features.space.nms

import net.minecraft.core.Holder
import net.minecraft.core.HolderSet
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.tags.BlockTags
import net.minecraft.util.valueproviders.ConstantInt
import net.minecraft.util.valueproviders.IntProvider
import net.minecraft.world.attribute.EnvironmentAttributeMap
import net.minecraft.world.clock.WorldClock
import net.minecraft.world.level.CardinalLighting
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.dimension.DimensionType
import net.minecraft.world.timeline.Timeline
import java.util.Optional

/**
 * Builds [DimensionType] records. Sole call site of the constructor, so a Mojang component
 * addition is one compile error here instead of one per dimension.
 */
object NmsDimensionType {

	/**
	 * Builds a dimension type. [minY] and [height] must be multiples of 16, [height] at least 16,
	 * and `minY + height` no higher than [DimensionType.MAX_Y] + 1 — the record validates all three.
	 *
	 * [attributes] carries the visual environment: fog colour, sky colour, fog distances. Every
	 * attribute in [net.minecraft.world.attribute.EnvironmentAttributes] under `visual/` is syncable
	 * and reaches the client through the dimension type's network codec.
	 */
	fun build(

		minY: Int,
		height: Int,
		attributes: EnvironmentAttributeMap = EnvironmentAttributeMap.EMPTY,
		skybox: DimensionType.Skybox = DimensionType.Skybox.NONE,
		hasSkyLight: Boolean = false,
		hasCeiling: Boolean = false,
		hasFixedTime: Boolean = true,
		ambientLight: Float = 0.0f,
		coordinateScale: Double = 1.0,
		logicalHeight: Int = height,
		infiniburn: HolderSet<Block> = HolderSet.empty(),
		monsterSpawnLightTest: IntProvider = ConstantInt.of(0),
		monsterSpawnBlockLightLimit: Int = 0,
		cardinalLightType: CardinalLighting.Type = CardinalLighting.Type.DEFAULT,
		timelines: HolderSet<Timeline> = HolderSet.empty(),
		defaultClock: Holder<WorldClock>? = null,

	): DimensionType = DimensionType(
		hasFixedTime,
		hasSkyLight,
		hasCeiling,
		false, // hasEnderDragonFight
		coordinateScale,
		minY,
		height,
		logicalHeight,
		infiniburn,
		ambientLight,
		DimensionType.MonsterSettings(monsterSpawnLightTest, monsterSpawnBlockLightLimit),
		skybox,
		cardinalLightType,
		attributes,
		timelines,
		Optional.ofNullable(defaultClock),
	)

	/** a HolderSet over the given timeline identifiers, for [build]'s `timelines`. */
	fun timelinesOf(

		vararg identifiers: Identifier

	): HolderSet<Timeline> {

		val holders = identifiers.map { identifier ->
			NmsRegistry.getTimeline(identifier)
				?: throw IllegalStateException("Missing timeline $identifier. Register it before the dimension type that uses it.")
		}

		return HolderSet.direct(holders)

	}

	/** the vanilla infiniburn tag, for a dimension that should burn like the overworld. */
	fun overworldInfiniburn(): HolderSet<Block> =
		MinecraftServer.getServer().registryAccess()
			.lookupOrThrow(Registries.BLOCK)
			.getOrThrow(BlockTags.INFINIBURN_OVERWORLD)

}
