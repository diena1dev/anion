package dev.diena.anion.features.space.validation.checks

import dev.diena.anion.features.space.nms.NmsDimensionType
import dev.diena.anion.features.space.nms.NmsRegistry
import dev.diena.anion.features.space.validation.CheckGroup
import dev.diena.anion.features.space.validation.Validation
import net.minecraft.core.RegistrationInfo
import net.minecraft.core.registries.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.tags.BiomeTags
import net.minecraft.world.level.dimension.DimensionType

/**
 * The reflective thaw/register/refreeze cycle. This is the only code in Anion that writes into a
 * frozen registry, so it is the part most worth proving before anything is built on it.
 */
object NmsRegistryChecks {

	val group = CheckGroup(

		name = "nms-registry",
		description = "frozen registry thaw, insert and refreeze",
		destructive = true, // registry entries cannot be removed until restart

	).apply {

		check("registry is frozen before we touch it") {

			val registry = MinecraftServer.getServer().registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE)

			requireThrows("raw register on a frozen registry") {
				@Suppress("UNCHECKED_CAST")
				(registry as net.minecraft.core.MappedRegistry<DimensionType>).register(
					net.minecraft.resources.ResourceKey.create(Registries.DIMENSION_TYPE, Validation.scratchIdentifier("raw")),
					NmsDimensionType.build(minY = 0, height = 16),
					RegistrationInfo.BUILT_IN
				)
			}

		}

		check("register returns a bound holder") {

			val identifier = Validation.scratchIdentifier("bound")
			val holder = NmsRegistry.setDimensionType(identifier, NmsDimensionType.build(minY = 0, height = 16))

			require(holder.isBound, "holder was not bound after register")
			requireEquals(identifier, holder.key().identifier(), "holder key")

		}

		check("registered value is readable back") {

			val identifier = Validation.scratchIdentifier("readback")
			val dimensionType = NmsDimensionType.build(minY = -64, height = 384)

			NmsRegistry.setDimensionType(identifier, dimensionType)

			val fetched = NmsRegistry.getDimensionType(identifier)
			requireNotNull(fetched, "getDimensionType after set")
			requireEquals(dimensionType, fetched!!.value(), "round tripped dimension type")

		}

		check("registry is frozen again afterwards") {

			NmsRegistry.setDimensionType(Validation.scratchIdentifier("refreeze"), NmsDimensionType.build(minY = 0, height = 16))

			val registry = MinecraftServer.getServer().registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE)

			requireThrows("raw register after refreeze") {
				@Suppress("UNCHECKED_CAST")
				(registry as net.minecraft.core.MappedRegistry<DimensionType>).register(
					net.minecraft.resources.ResourceKey.create(Registries.DIMENSION_TYPE, Validation.scratchIdentifier("raw2")),
					NmsDimensionType.build(minY = 0, height = 16),
					RegistrationInfo.BUILT_IN
				)
			}

		}

		check("duplicate identifier is rejected") {

			val identifier = Validation.scratchIdentifier("duplicate")
			NmsRegistry.setDimensionType(identifier, NmsDimensionType.build(minY = 0, height = 16))

			val thrown = requireThrows("second register under the same identifier") {
				NmsRegistry.setDimensionType(identifier, NmsDimensionType.build(minY = 0, height = 32))
			}

			require(thrown is IllegalStateException, "expected IllegalStateException, got ${thrown::class.simpleName}")

		}

		// an entry the client is never told about renders as a missing dimension on join
		check("registration info carries no known pack, so the entry syncs") {

			val identifier = Validation.scratchIdentifier("syncable")
			NmsRegistry.setDimensionType(identifier, NmsDimensionType.build(minY = 0, height = 16))

			val registry = MinecraftServer.getServer().registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE)
			val resourceKey = net.minecraft.resources.ResourceKey.create(Registries.DIMENSION_TYPE, identifier)
			val info = registry.registrationInfo(resourceKey).orElse(null)

			requireNotNull(info, "registration info")
			require(info!!.knownPackInfo().isEmpty, "known pack info must be empty or the client is assumed to have the entry")

		}

		check("entry lands at the end of the id space") {

			val registry = MinecraftServer.getServer().registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE)
			val sizeBefore = registry.size()

			val identifier = Validation.scratchIdentifier("idspace")
			NmsRegistry.setDimensionType(identifier, NmsDimensionType.build(minY = 0, height = 16))

			requireEquals(sizeBefore + 1, registry.size(), "registry size after insert")

			val fetched = NmsRegistry.getDimensionType(identifier)!!
			requireEquals(sizeBefore, registry.getId(fetched.value()), "new entry id")

		}

		// freeze() rebuilds allTags from frozenTags; if the thaw dropped them this fails
		check("refreeze does not destroy existing tags") {

			val biomes = MinecraftServer.getServer().registryAccess().lookupOrThrow(Registries.BIOME)
			val overworldBefore = biomes.getOrThrow(BiomeTags.IS_OVERWORLD).size()

			require(overworldBefore > 0, "is_overworld tag was already empty, check is meaningless")

			NmsRegistry.setDimensionType(Validation.scratchIdentifier("tagsurvival"), NmsDimensionType.build(minY = 0, height = 16))

			val overworldAfter = biomes.getOrThrow(BiomeTags.IS_OVERWORLD).size()
			requireEquals(overworldBefore, overworldAfter, "is_overworld tag size across a refreeze")

		}

		check("previously registered holders stay bound across a later refreeze") {

			val identifier = Validation.scratchIdentifier("stillbound")
			val holder = NmsRegistry.setDimensionType(identifier, NmsDimensionType.build(minY = 0, height = 16))

			NmsRegistry.setDimensionType(Validation.scratchIdentifier("later"), NmsDimensionType.build(minY = 0, height = 16))

			require(holder.isBound, "earlier holder came unbound after a later register")

		}

		check("keys() lists what we registered") {

			val identifier = Validation.scratchIdentifier("listed")
			NmsRegistry.setDimensionType(identifier, NmsDimensionType.build(minY = 0, height = 16))

			require(identifier in NmsRegistry.keys(Registries.DIMENSION_TYPE), "identifier missing from keys()")

		}

		check("get returns null for an unregistered identifier") {

			requireEquals(null, NmsRegistry.getDimensionType(Validation.scratchIdentifier("never")), "get on a missing key")

		}

	}

}
