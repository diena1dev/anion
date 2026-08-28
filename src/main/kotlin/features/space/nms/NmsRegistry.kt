package dev.diena.anion.features.space.nms

import com.mojang.serialization.Lifecycle
import net.minecraft.core.Holder
import net.minecraft.core.MappedRegistry
import net.minecraft.core.RegistrationInfo
import net.minecraft.core.Registry
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.dimension.DimensionType
import net.minecraft.world.timeline.Timeline

/**
 * Writes into registries the server has already frozen. Every reflective touch of NMS registry
 * internals in Anion lives here, so a Mojang field rename is a one-function fix.
 */
object NmsRegistry {

	////////////////////////
	///// REFLECTION HANDLES
	////////////////////////

	private val frozenField = MappedRegistry::class.java.getDeclaredField("frozen")
		.apply { isAccessible = true }

	private val allTagsField = MappedRegistry::class.java.getDeclaredField("allTags")
		.apply { isAccessible = true }

	/** an unbound TagSet, read off a throwaway registry. MappedRegistry.TagSet is a private
	 *  interface, so this is the only way to get one without reflecting on the interface itself. */
	private val unboundTagSet: Any by lazy {
		val scratchKey = ResourceKey.createRegistryKey<Any>(Identifier.fromNamespaceAndPath("anion", "scratch"))

		allTagsField.get(MappedRegistry(scratchKey, Lifecycle.stable()))
	}

	//////////////////////
	///// GENERIC REGISTRY
	//////////////////////

	private fun <T: Any> lookup(

		registryKey: ResourceKey<out Registry<T>>

	): MappedRegistry<T> {

		val registry = MinecraftServer.getServer().registryAccess().lookupOrThrow(registryKey)

		return registry as? MappedRegistry<T>
			?: throw IllegalStateException("Registry $registryKey is not a MappedRegistry, cannot write to it")

	}

	/**
	 * Adds [value] under [identifier] and refreezes. Returns the holder.
	 *
	 * Main thread only, and before the first player connects: registry ids are assigned in
	 * registration order and shift for anyone already synced.
	 */
	fun <T: Any> register(

		registryKey: ResourceKey<out Registry<T>>,
		identifier: Identifier,
		value: T,

	): Holder.Reference<T> {

		val registry = lookup(registryKey)
		val resourceKey = ResourceKey.create(registryKey, identifier)

		if (registry.containsKey(resourceKey)) {
			throw IllegalStateException("Duplicate key $identifier in registry $registryKey. Fix your registrations!")
		}

		frozenField.setBoolean(registry, false)
		allTagsField.set(registry, unboundTagSet) // freeze() refuses to run with a bound tag set

		// BUILT_IN carries no KnownPack, so RegistrySynchronization always sends this entry's
		// contents to the client rather than assuming the client already has it
		val holder = registry.register(resourceKey, value, RegistrationInfo.BUILT_IN)

		// rebuilds componentLookup, rebinds tags from frozenTags, and binds the new holder
		registry.freeze()

		return holder

	}

	/** the holder for [identifier], or null if nothing is registered under it. */
	fun <T: Any> get(

		registryKey: ResourceKey<out Registry<T>>,
		identifier: Identifier,

	): Holder.Reference<T>? =
		MinecraftServer.getServer().registryAccess()
			.lookupOrThrow(registryKey)
			.get(identifier)
			.orElse(null)

	/** every identifier currently in [registryKey]. */
	fun <T: Any> keys(

		registryKey: ResourceKey<out Registry<T>>

	): Set<Identifier> =
		MinecraftServer.getServer().registryAccess().lookupOrThrow(registryKey).keySet()

	///////////////////
	///// TYPED ACCESS
	///////////////////

	fun setDimensionType(

		identifier: Identifier,
		dimensionType: DimensionType,

	): Holder.Reference<DimensionType> = register(Registries.DIMENSION_TYPE, identifier, dimensionType)

	fun getDimensionType(

		identifier: Identifier

	): Holder.Reference<DimensionType>? = get(Registries.DIMENSION_TYPE, identifier)

	fun setBiome(

		identifier: Identifier,
		biome: Biome,

	): Holder.Reference<Biome> = register(Registries.BIOME, identifier, biome)

	fun getBiome(

		identifier: Identifier

	): Holder.Reference<Biome>? = get(Registries.BIOME, identifier)

	fun setTimeline(

		identifier: Identifier,
		timeline: Timeline,

	): Holder.Reference<Timeline> = register(Registries.TIMELINE, identifier, timeline)

	fun getTimeline(

		identifier: Identifier

	): Holder.Reference<Timeline>? = get(Registries.TIMELINE, identifier)

}
