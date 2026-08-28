package dev.diena.anion.features.space.validation.checks

import com.mojang.serialization.JsonOps
import dev.diena.anion.features.space.nms.NmsBiome
import dev.diena.anion.features.space.nms.NmsRegistry
import dev.diena.anion.features.space.validation.CheckGroup
import dev.diena.anion.features.space.validation.Validation
import net.minecraft.core.registries.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.world.attribute.EnvironmentAttributeMap
import net.minecraft.world.attribute.EnvironmentAttributes
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.BiomeGenerationSettings
import net.minecraft.world.level.biome.MobSpawnSettings

/**
 * Biome construction and registration. Biome is a synced registry, so the network codec check is
 * the one that decides whether a chunk carrying this biome can be sent at all.
 */
object NmsBiomeChecks {

	val group = CheckGroup(

		name = "nms-biome",
		description = "biome construction, encoding and registration",
		destructive = true,

	).apply {

		check("defaults build a barren biome") {

			val biome = NmsBiome.build()

			requireEquals(false, biome.hasPrecipitation(), "hasPrecipitation")
			requireEquals(0.5f, biome.baseTemperature, "baseTemperature")

		}

		check("generation and mob settings default to empty") {

			val biome = NmsBiome.build()

			requireEquals(0, biome.generationSettings.features().size, "feature steps on an empty biome")
			requireEquals(MobSpawnSettings.EMPTY.creatureProbability, biome.mobSettings.creatureProbability, "creature spawn probability")

		}

		check("the builder still rejects a half-filled biome") {

			// NmsBiome.build defaults everything, so this proves the underlying builder guard is intact
			requireThrows("BiomeBuilder with no climate") {
				Biome.BiomeBuilder()
					.generationSettings(BiomeGenerationSettings.EMPTY)
					.mobSpawnSettings(MobSpawnSettings.EMPTY)
					.build()
			}

		}

		check("attributes reach the biome") {

			val attributes = EnvironmentAttributeMap.builder()
				.set(EnvironmentAttributes.FOG_COLOR, 0x101020)
				.build()

			val biome = NmsBiome.build(attributes = attributes)

			// no public getter for the map, so the codec is the observation point
			val ops = MinecraftServer.getServer().registryAccess().createSerializationContext(JsonOps.INSTANCE)
			val encoded = Biome.DIRECT_CODEC.encodeStart(ops, biome)
				.getOrThrow { message -> AssertionError("encode failed: $message") }

			require(encoded.toString().contains("attributes"), "attributes absent from the encoded biome")

		}

		check("direct codec round trips") {

			val biome = NmsBiome.build(temperature = 0.0f, waterColor = 0x112233)
			val ops = MinecraftServer.getServer().registryAccess().createSerializationContext(JsonOps.INSTANCE)

			val encoded = Biome.DIRECT_CODEC.encodeStart(ops, biome)
				.getOrThrow { message -> AssertionError("encode failed: $message") }

			val decoded = Biome.DIRECT_CODEC.parse(ops, encoded)
				.getOrThrow { message -> AssertionError("decode failed: $message") }

			requireEquals(0.0f, decoded.baseTemperature, "temperature after round trip")

		}

		// chunk packets encode biomes by registry id, and the client decodes with this codec
		check("network codec encodes what the client is sent") {

			val biome = NmsBiome.build(waterColor = 0x112233)
			val ops = MinecraftServer.getServer().registryAccess().createSerializationContext(JsonOps.INSTANCE)

			val encoded = Biome.NETWORK_CODEC.encodeStart(ops, biome)
				.getOrThrow { message -> AssertionError("network encode failed: $message") }

			val decoded = Biome.NETWORK_CODEC.parse(ops, encoded)
				.getOrThrow { message -> AssertionError("network decode failed: $message") }

			requireNotNull(decoded, "biome decoded from the network codec")

		}

		check("a built biome registers and reads back") {

			val identifier = Validation.scratchIdentifier("biome")
			val biome = NmsBiome.build(waterColor = 0x0A0A0A)

			val holder = NmsRegistry.setBiome(identifier, biome)

			require(holder.isBound, "biome holder not bound")
			requireEquals(biome, NmsRegistry.getBiome(identifier)?.value(), "biome read back")

		}

		check("a registered biome has a stable network id") {

			val identifier = Validation.scratchIdentifier("biomeid")
			NmsRegistry.setBiome(identifier, NmsBiome.build())

			val registry = MinecraftServer.getServer().registryAccess().lookupOrThrow(Registries.BIOME)
			val value = NmsRegistry.getBiome(identifier)!!.value()

			require(registry.getId(value) >= 0, "registered biome has no id, chunk packets cannot encode it")

		}

	}

}
