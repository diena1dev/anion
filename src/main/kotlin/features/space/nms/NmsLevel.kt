package dev.diena.anion.features.space.nms

import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Util
import net.minecraft.world.level.CustomSpawner
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.BiomeManager
import net.minecraft.world.level.dimension.LevelStem
import net.minecraft.world.level.levelgen.WorldGenSettings
import net.minecraft.world.level.levelgen.WorldOptions
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.level.storage.PrimaryLevelData
import net.minecraft.world.level.storage.SavedDataStorage
import io.papermc.paper.math.Position
import io.papermc.paper.world.PaperWorldLoader
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.generator.BiomeProvider
import org.bukkit.generator.ChunkGenerator

/**
 * Creates worlds from a caller-supplied [LevelStem]. `CraftServer.createWorld` only ever picks the
 * overworld/nether/end stems out of the registry, so a custom dimension type or chunk generator has
 * to construct the level itself. Sole call site of the ServerLevel constructor.
 */
object NmsLevel {

	/**
	 * Launders a null past a wrong nullability annotation. `net.minecraft.server.level` is
	 * `@NullMarked`, so Kotlin reads the Paper-added `gen` and `biomeProvider` parameters as
	 * non-null even though the constructor body guards both with `if (x != null)`. The cast erases,
	 * so nothing is checked at runtime.
	 */
	@Suppress("UNCHECKED_CAST")
	private fun <T> nullable(value: T?): T = value as T

	/**
	 * Builds and registers a level running [levelStem], returning the Bukkit view of it.
	 *
	 * Since 26.1 Bukkit treats a world's name and key as one identity, deriving the name from the
	 * key as `namespace_key` for a namespaced key. Nothing here enforces that, but a [bukkitName]
	 * that disagrees with [worldKey] leaves `getWorld(name)` and `getWorld(key)` describing the
	 * same world by two unrelated identities.
	 *
	 * [spawnPosition] skips the spawn search, which never terminates usefully in a void world.
	 * [bukkitGenerator] and [bukkitBiomeProvider] are optional overlays on top of the stem's own
	 * generator; leave both null when the stem already carries an Anion chunk generator.
	 */
	fun create(

		bukkitName: String,
		worldKey: NamespacedKey,
		levelStem: LevelStem,
		seed: Long,
		environment: World.Environment = World.Environment.CUSTOM,
		spawnPosition: Position? = null,
		generateStructures: Boolean = false,
		bukkitGenerator: ChunkGenerator? = null,
		bukkitBiomeProvider: BiomeProvider? = null,

	): World {

		val server = MinecraftServer.getServer()

		require(Bukkit.getWorld(bukkitName) == null && Bukkit.getWorld(worldKey) == null) {
			"A world already exists with name $bukkitName or key $worldKey"
		}

		val dimensionKey: ResourceKey<Level> = ResourceKey.create(
			Registries.DIMENSION,
			Identifier.fromNamespaceAndPath(worldKey.namespace, worldKey.key)
		)

		val loadedWorldData = PaperWorldLoader.loadWorldData(server, dimensionKey, bukkitName)
		val primaryLevelData = server.worldData as PrimaryLevelData

		// the stem is handed over separately below; these settings only have to be serialisable,
		// since SavedDataStorage encodes them on every world save
		val worldGenSettings = WorldGenSettings(
			WorldOptions(seed, generateStructures, false),
			server.worldGenSettings.dimensions()
		)

		val savedDataStorage = SavedDataStorage(
			server.storageSource.getDimensionPath(dimensionKey).resolve(LevelResource.DATA.id()),
			server.fixerUpper,
			server.registryAccess()
		)
		savedDataStorage.set(WorldGenSettings.TYPE, worldGenSettings)

		// constructing the level registers it with CraftServer.worlds on its own
		val serverLevel = ServerLevel(
			server,
			Util.backgroundExecutor(),
			server.storageSource,
			worldGenSettings,
			dimensionKey,
			levelStem,
			primaryLevelData.isDebugWorld,
			BiomeManager.obfuscateSeed(seed),
			emptyList<CustomSpawner>(), // no phantoms, patrols, cats, sieges or wandering traders
			true,
			LevelStem.OVERWORLD, // stem slot key, only read for the flat-world check
			environment,
			nullable(bukkitGenerator),
			nullable(bukkitBiomeProvider),
			savedDataStorage,
			loadedWorldData
		)

		// purely a carrier for the forced spawn: initWorld reads nothing off it but the spawn
		// position, yaw and pitch. ofKey rather than ofNameAndKey, which demands the key be
		// minecraft:<lowercased name> and rejects any namespaced key outright.
		val spawnCarrier = spawnPosition?.let {
			WorldCreator.ofKey(worldKey).forcedSpawnPosition(it, 0.0f, 0.0f)
		}

		server.addLevel(serverLevel)
		server.initWorld(serverLevel, spawnCarrier)
		serverLevel.setSpawnSettings(true)
		server.prepareLevel(serverLevel)

		return serverLevel.world

	}

}
