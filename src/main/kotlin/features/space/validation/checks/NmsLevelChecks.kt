package dev.diena.anion.features.space.validation.checks

import dev.diena.anion.features.space.nms.NmsDimensionType
import dev.diena.anion.features.space.nms.NmsLevel
import dev.diena.anion.features.space.nms.NmsRegistry
import dev.diena.anion.features.space.validation.CheckGroup
import dev.diena.anion.features.space.validation.Validation
import dev.diena.anion.features.space.validation.support.ShapeChunkGenerator
import dev.diena.anion.features.space.validation.support.SpaceShapes
import io.papermc.paper.math.Position
import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.world.attribute.EnvironmentAttributeMap
import net.minecraft.world.attribute.EnvironmentAttributes
import net.minecraft.world.level.dimension.DimensionType
import net.minecraft.world.level.dimension.LevelStem
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.craftbukkit.CraftWorld

/**
 * End to end: a custom dimension type plus a custom chunk generator becomes a live world with the
 * right height, sky and blocks.
 *
 * Creates world folders on disk and leaves them behind — worlds are unloaded but not deleted, since
 * removing a directory is not this harness's call to make. Paths are reported at the end.
 */
object NmsLevelChecks {

	private const val PLANE_Y = 64
	private const val INNER_RADIUS = 40.0
	private const val OUTER_RADIUS = 52.0
	private const val VERTICAL_THICKNESS = 2

	private val createdFolders = mutableListOf<String>()

	fun createdWorldFolders(): List<String> = createdFolders.toList()

	private val ring = SpaceShapes.ring(
		centerX = 0,
		centerZ = 0,
		planeY = PLANE_Y,
		innerRadius = INNER_RADIUS,
		outerRadius = OUTER_RADIUS,
		verticalThickness = VERTICAL_THICKNESS,
	)

	private fun spaceDimensionType(): DimensionType = NmsDimensionType.build(
		minY = -64,
		height = 512,
		attributes = EnvironmentAttributeMap.builder()
			.set(EnvironmentAttributes.FOG_COLOR, 0x05050C)
			.set(EnvironmentAttributes.SKY_COLOR, 0x000000)
			.build(),
	)

	private fun voidBiome(): Holder<net.minecraft.world.level.biome.Biome> =
		MinecraftServer.getServer().registryAccess()
			.lookupOrThrow(Registries.BIOME)
			.get(net.minecraft.resources.Identifier.withDefaultNamespace("the_void"))
			.orElseThrow { IllegalStateException("minecraft:the_void biome missing") }

	/** builds a world running the ring generator. throws rather than returning null, so a failure
	 *  to create is a failed check instead of a silently skipped one. */
	private fun createRingWorld(label: String): org.bukkit.World {

		val dimensionHolder = NmsRegistry.setDimensionType(Validation.scratchIdentifier(label), spaceDimensionType())
		val generator = ShapeChunkGenerator(voidBiome(), ring, minY = -64, genDepth = 512)
		val stem = LevelStem(dimensionHolder, generator)

		// one timestamp for both: two calls can land on different milliseconds and desync them
		val stamp = System.currentTimeMillis()
		val worldKey = NamespacedKey("anion_validation", "${label}_$stamp")
		val worldName = "${worldKey.namespace}_${worldKey.key}" // Paper's own name-from-key convention

		val world = NmsLevel.create(
			bukkitName = worldName,
			worldKey = worldKey,
			levelStem = stem,
			seed = 1234L,
			spawnPosition = Position.block(0, PLANE_Y + 8, 0),
		)

		createdFolders += world.worldFolder.absolutePath

		return world

	}

	val group = CheckGroup(

		name = "nms-level",
		description = "custom dimension type and generator become a live world",
		destructive = true, // writes world folders, registers dimension types

	).apply {

		check("no players online, ids are about to shift") {

			// registering a dimension type renumbers the registry for anyone already synced
			require(Bukkit.getOnlinePlayers().size <= 1, "run this with at most the operator online")

		}

		check("a world builds from a custom stem") {

			val world = createRingWorld("build")

			try {
				requireNotNull(Bukkit.getWorld(world.name), "world missing from Bukkit.getWorlds()")
			} finally {
				Bukkit.unloadWorld(world, false)
			}

		}

		check("custom height reaches the Bukkit view") {

			val world = createRingWorld("height") 

			try {
				requireEquals(-64, world.minHeight, "world minHeight")
				requireEquals(448, world.maxHeight, "world maxHeight")
			} finally {
				Bukkit.unloadWorld(world, false)
			}

		}

		check("the level runs our dimension type") {

			val world = createRingWorld("dimtype")

			try {

				val dimensionType = (world as CraftWorld).handle.dimensionType()

				requireEquals(DimensionType.Skybox.NONE, dimensionType.skybox(), "skybox on the live level")
				requireEquals(false, dimensionType.hasSkyLight(), "hasSkyLight on the live level")
				require(dimensionType.attributes().contains(EnvironmentAttributes.FOG_COLOR), "fog colour lost on the live level")

			} finally {
				Bukkit.unloadWorld(world, false)
			}

		}

		check("the level runs our chunk generator") {

			val world = createRingWorld("generator")

			try {

				val generator = (world as CraftWorld).handle.chunkSource.generator
				require(generator is ShapeChunkGenerator, "generator was ${generator::class.simpleName}, not ours")

			} finally {
				Bukkit.unloadWorld(world, false)
			}

		}

		check("generated chunks carry the ring and nothing else") {

			val world = createRingWorld("blocks")

			try {

				// a column through the band, and one through the hole
				world.getChunkAt(45 shr 4, 0)
				world.getChunkAt(0, 0)

				requireEquals(Material.STONE, world.getBlockAt(45, PLANE_Y, 0).type, "block inside the ring band")
				requireEquals(Material.AIR, world.getBlockAt(45, PLANE_Y + 8, 0).type, "block above the ring band")
				requireEquals(Material.AIR, world.getBlockAt(0, PLANE_Y, 0).type, "block in the ring hole")

			} finally {
				Bukkit.unloadWorld(world, false)
			}

		}

		check("the ring closes all the way round") {

			val world = createRingWorld("closure")

			try {

				for (degrees in 0 until 360 step 45) {

					val radians = Math.toRadians(degrees.toDouble())
					val blockX = (Math.cos(radians) * 46.0).toInt()
					val blockZ = (Math.sin(radians) * 46.0).toInt()

					world.getChunkAt(blockX shr 4, blockZ shr 4)

					requireEquals(
						Material.STONE,
						world.getBlockAt(blockX, PLANE_Y, blockZ).type,
						"ring block at $degrees degrees ($blockX, $blockZ)",
					)

				}

			} finally {
				Bukkit.unloadWorld(world, false)
			}

		}

		check("a duplicate world name is refused") {

			val world = createRingWorld("dupe")

			try {

				requireThrows("second create under the same name") {
					NmsLevel.create(
						bukkitName = world.name,
						worldKey = NamespacedKey("anion_validation", "dupe_again"),
						levelStem = LevelStem(
							NmsRegistry.setDimensionType(Validation.scratchIdentifier("dupe2"), spaceDimensionType()),
							ShapeChunkGenerator(voidBiome(), ring),
						),
						seed = 1L,
					)
				}

			} finally {
				Bukkit.unloadWorld(world, false)
			}

		}

		check("the world unloads cleanly") {

			val world = createRingWorld("unload")
			val name = world.name

			require(Bukkit.unloadWorld(world, false), "unloadWorld returned false")
			requireEquals(null, Bukkit.getWorld(name), "world still present after unload")

		}

	}

}
