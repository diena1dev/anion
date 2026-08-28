package dev.diena.anion.features.space.nms

import net.minecraft.world.attribute.EnvironmentAttributeMap
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.BiomeGenerationSettings
import net.minecraft.world.level.biome.BiomeSpecialEffects
import net.minecraft.world.level.biome.MobSpawnSettings

/**
 * Builds [Biome] values. Sole call site of [Biome.BiomeBuilder], so a builder change is one
 * compile error here.
 *
 * Fog and sky colour are **not** biome properties in this version — they moved onto
 * [EnvironmentAttributeMap], carried by both biomes and dimension types. Set them per-dimension in
 * [NmsDimensionType], or here when they should vary between biomes in the same world.
 */
object NmsBiome {

	/**
	 * Builds a biome. [BiomeSpecialEffects] only carries water/foliage/grass colours now, so
	 * [attributes] is where visual overrides go.
	 */
	fun build(

		temperature: Float = 0.5f,
		downfall: Float = 0.0f,
		hasPrecipitation: Boolean = false,
		waterColor: Int = 0x3F76E4,
		attributes: EnvironmentAttributeMap = EnvironmentAttributeMap.EMPTY,
		generationSettings: BiomeGenerationSettings = BiomeGenerationSettings.EMPTY,
		mobSpawnSettings: MobSpawnSettings = MobSpawnSettings.EMPTY,
		specialEffects: BiomeSpecialEffects = BiomeSpecialEffects.Builder().waterColor(waterColor).build(),

	): Biome = Biome.BiomeBuilder()
		.hasPrecipitation(hasPrecipitation)
		.temperature(temperature)
		.downfall(downfall)
		.putAttributes(attributes)
		.specialEffects(specialEffects)
		.generationSettings(generationSettings)
		.mobSpawnSettings(mobSpawnSettings)
		.build()

}
