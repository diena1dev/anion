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
	 * Builds and registers a level running [levelStem], returning the Bukkit view of it.
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
			bukkitGenerator,
			bukkitBiomeProvider,
			savedDataStorage,
			loadedWorldData
		)

		val spawnCarrier = spawnPosition?.let {
			WorldCreator.ofNameAndKey(bukkitName, worldKey).forcedSpawnPosition(it, 0.0f, 0.0f)
		}

		server.addLevel(serverLevel)
		server.initWorld(serverLevel, spawnCarrier)
		serverLevel.setSpawnSettings(true)
		server.prepareLevel(serverLevel)

		return serverLevel.world

	}

}
