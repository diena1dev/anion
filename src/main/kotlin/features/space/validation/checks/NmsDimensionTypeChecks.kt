package dev.diena.anion.features.space.validation.checks

import com.mojang.serialization.JsonOps
import dev.diena.anion.features.space.nms.NmsDimensionType
import dev.diena.anion.features.space.validation.CheckGroup
import net.minecraft.server.MinecraftServer
import net.minecraft.world.attribute.EnvironmentAttributeMap
import net.minecraft.world.attribute.EnvironmentAttributes
import net.minecraft.world.level.dimension.DimensionType

/**
 * The dimension type record: its own validation rules, and whether a built type survives the
 * network codec. Anything that fails the network round trip cannot be sent to a client.
 */
object NmsDimensionTypeChecks {

	private const val FOG = 0x0A0A14
	private const val SKY = 0x000006

	val group = CheckGroup(

		name = "nms-dimension-type",
		description = "dimension type construction, validation and client encoding",

	).apply {

		////////////////////////
		///// RECORD VALIDATION
		////////////////////////

		check("height must be a multiple of 16") {
			requireThrows("height 100") { NmsDimensionType.build(minY = 0, height = 100) }
		}

		check("height must be at least 16") {
			requireThrows("height 0") { NmsDimensionType.build(minY = 0, height = 0) }
		}

		check("minY must be a multiple of 16") {
			requireThrows("minY -60") { NmsDimensionType.build(minY = -60, height = 128) }
		}

		check("logicalHeight cannot exceed height") {
			requireThrows("logicalHeight 512 with height 128") {
				NmsDimensionType.build(minY = 0, height = 128, logicalHeight = 512)
			}
		}

		check("minY + height cannot exceed the y limit") {
			requireThrows("minY 0 with an absurd height") {
				NmsDimensionType.build(minY = 0, height = DimensionType.Y_SIZE + 16)
			}
		}

		check("a legal tall void dimension builds") {

			val dimensionType = NmsDimensionType.build(minY = -1024, height = 2048)

			requireEquals(-1024, dimensionType.minY(), "minY")
			requireEquals(2048, dimensionType.height(), "height")
			requireEquals(2048, dimensionType.logicalHeight(), "logicalHeight defaults to height")

		}

		//////////////////
		///// SPACE SHAPE
		//////////////////

		check("defaults describe a space dimension") {

			val dimensionType = NmsDimensionType.build(minY = 0, height = 256)

			requireEquals(DimensionType.Skybox.NONE, dimensionType.skybox(), "skybox")
			requireEquals(false, dimensionType.hasSkyLight(), "hasSkyLight")
			requireEquals(false, dimensionType.hasCeiling(), "hasCeiling")
			requireEquals(false, dimensionType.hasEnderDragonFight(), "hasEnderDragonFight")
			requireEquals(0.0f, dimensionType.ambientLight(), "ambientLight")

		}

		check("fog and sky colour survive onto the record") {

			val attributes = EnvironmentAttributeMap.builder()
				.set(EnvironmentAttributes.FOG_COLOR, FOG)
				.set(EnvironmentAttributes.SKY_COLOR, SKY)
				.build()

			val dimensionType = NmsDimensionType.build(minY = 0, height = 256, attributes = attributes)

			require(dimensionType.attributes().contains(EnvironmentAttributes.FOG_COLOR), "fog colour missing")
			require(dimensionType.attributes().contains(EnvironmentAttributes.SKY_COLOR), "sky colour missing")

		}

		check("fog attributes are syncable, or the client never sees them") {

			require(EnvironmentAttributes.FOG_COLOR.isSyncable, "FOG_COLOR is not syncable")
			require(EnvironmentAttributes.SKY_COLOR.isSyncable, "SKY_COLOR is not syncable")
			require(EnvironmentAttributes.FOG_START_DISTANCE.isSyncable, "FOG_START_DISTANCE is not syncable")
			require(EnvironmentAttributes.FOG_END_DISTANCE.isSyncable, "FOG_END_DISTANCE is not syncable")

		}

		//////////////
		///// CODECS
		//////////////

		check("direct codec round trips") {

			val dimensionType = NmsDimensionType.build(
				minY = -512,
				height = 1024,
				attributes = EnvironmentAttributeMap.builder().set(EnvironmentAttributes.FOG_COLOR, FOG).build(),
			)

			val ops = MinecraftServer.getServer().registryAccess().createSerializationContext(JsonOps.INSTANCE)

			val encoded = DimensionType.DIRECT_CODEC.encodeStart(ops, dimensionType)
				.getOrThrow { message -> AssertionError("encode failed: $message") }

			val decoded = DimensionType.DIRECT_CODEC.parse(ops, encoded)
				.getOrThrow { message -> AssertionError("decode failed: $message") }

			requireEquals(dimensionType.minY(), decoded.minY(), "minY after round trip")
			requireEquals(dimensionType.height(), decoded.height(), "height after round trip")
			requireEquals(dimensionType.skybox(), decoded.skybox(), "skybox after round trip")

		}

		// the client is sent DimensionType.NETWORK_CODEC, so this is the join-or-crash check
		check("network codec encodes what the client is sent") {

			val dimensionType = NmsDimensionType.build(
				minY = 0,
				height = 256,
				attributes = EnvironmentAttributeMap.builder()
					.set(EnvironmentAttributes.FOG_COLOR, FOG)
					.set(EnvironmentAttributes.SKY_COLOR, SKY)
					.build(),
			)

			val ops = MinecraftServer.getServer().registryAccess().createSerializationContext(JsonOps.INSTANCE)

			val encoded = DimensionType.NETWORK_CODEC.encodeStart(ops, dimensionType)
				.getOrThrow { message -> AssertionError("network encode failed: $message") }

			val decoded = DimensionType.NETWORK_CODEC.parse(ops, encoded)
				.getOrThrow { message -> AssertionError("network decode failed: $message") }

			requireEquals(DimensionType.Skybox.NONE, decoded.skybox(), "skybox over the network")
			require(decoded.attributes().contains(EnvironmentAttributes.FOG_COLOR), "fog colour did not survive the network codec")

		}

		check("timelinesOf rejects an unregistered timeline") {

			requireThrows("timelinesOf on a missing identifier") {
				NmsDimensionType.timelinesOf(dev.diena.anion.features.space.validation.Validation.scratchIdentifier("absent"))
			}

		}

		check("overworld infiniburn tag resolves") {

			require(NmsDimensionType.overworldInfiniburn().size() > 0, "infiniburn tag was empty")

		}

	}

}
