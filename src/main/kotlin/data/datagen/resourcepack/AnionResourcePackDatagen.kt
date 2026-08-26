package dev.diena.anion.data.datagen.resourcepack

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import dev.diena.anion.data.registry.registries.AnionRegistries
import dev.diena.anion.features.custom.blocks.AnionBlock
import dev.diena.anion.features.custom.blocks.AnionPillarBlock
import dev.diena.anion.features.custom.items.AnionBlockItem
import dev.diena.anion.features.transport.AnionTransportComponent
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument
import org.bukkit.craftbukkit.block.data.CraftBlockData
import java.io.File
import java.util.Base64

/** Iterates all registered AnionItems and AnionBlocks and produces a resource pack with generated model files. */
class AnionResourcePackDatagen(private val outputDir: File) {

	private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

	// i could not think of a better way of encoding the vanilla barrier texture.
	private val barrierTextureB64 = "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAXElEQVR42q2TyQ0AIAgEsf9qbMBmLMTjReLBEIVkf8zsByR0SkqtipiZO0dYlzh5ZG3GhjlXCcDrzlUAsBYYAoZBADAJAGYBwCwAmAVeWAX/h6STH095k3gS+sEdGgfXopZkuo0AAAAASUVORK5CYII="

	companion object {
		const val PACK_FORMAT = 88
	}

	/** Main function called when generating the resource pack. Add generation functions here. */
	fun generate() {

		val packRoot = File(outputDir, "generated/resourcepack")
		packRoot.deleteRecursively()
		packRoot.mkdirs()

		// generation functions
		writePackMeta(packRoot)
		writeItemModels(packRoot)
		writeTextures(packRoot)
		writeNoteBlockOverrides(packRoot)
		writeBlockAndBlockItemModels(packRoot)

	}

	private fun writePackMeta(packRoot: File) {

		val meta = JsonObject().apply {
			add("pack", JsonObject().apply {
				addProperty("pack_format", PACK_FORMAT)
				addProperty("min_format", PACK_FORMAT)
				addProperty("max_format", PACK_FORMAT)
				addProperty("description", "Anion Resource Pack")
			})
		}
		File(packRoot, "pack.mcmeta").writeText(gson.toJson(meta))

	}

	private fun writeItemModels(packRoot: File) {

		val itemsDir = File(packRoot, "assets/anion/items")
		itemsDir.mkdirs()

		AnionRegistries.ITEM_REGISTRY.all.values.forEach { item ->

			when (item) {

				// is a block item
				is AnionBlockItem -> {

					val key = item.namespacedKey.key
					val model = JsonObject().apply {
						add("model", JsonObject().apply {
							addProperty("type", "minecraft:model")
							addProperty("model", "anion:block/$key")
						})
					}
					File(itemsDir, "$key.json").writeText(gson.toJson(model))

				}

				// assume it's a normal item
				else -> {

					val key = item.namespacedKey.key
					val model = JsonObject().apply {
						add("model", JsonObject().apply {
							addProperty("type", "minecraft:model")
							addProperty("model", "anion:item/$key")
						})
					}
					File(itemsDir, "$key.json").writeText(gson.toJson(model))

				}

			}

		}

	}

	private fun writeTextures(packRoot: File) {

		val itemTexturesDir = File(packRoot, "assets/anion/textures/item")
		val blockTexturesDir = File(packRoot, "assets/anion/textures/block")
		itemTexturesDir.mkdirs()
		blockTexturesDir.mkdirs()

		val pngBytes = Base64.getDecoder().decode(barrierTextureB64)

		for (item in AnionRegistries.ITEM_REGISTRY.all.values) {

			if (item is AnionBlockItem) continue

			val key = item.namespacedKey.key

			File(itemTexturesDir, "$key.png").writeBytes(pngBytes)

		}

		// gen textures for blocks
		AnionRegistries.BLOCK_REGISTRY.all.values.forEach { block ->

			val key = block.namespacedKey.key

			File(blockTexturesDir, "$key.png").writeBytes(pngBytes)

		}

	}

	/**
	 * Vanilla model to stand in for a block whose own model has not been drawn yet, or null to emit the
	 * usual cube_all pointing at an anion texture.
	 *
	 * Uses purpur block models for Transport components.
	 */
	private fun placeholderModelParent(block: AnionBlock): String? = when (block) {

		is AnionPillarBlock -> "minecraft:block/purpur_pillar"
		is AnionTransportComponent -> "minecraft:block/purpur_block"
		else -> null

	}

	private fun writeBlockAndBlockItemModels(packRoot: File) {

		val blockModelsDir = File(packRoot, "assets/anion/models/block")
		val itemModelsDir = File(packRoot, "assets/anion/models/item")
		blockModelsDir.mkdirs()
		itemModelsDir.mkdirs()

		AnionRegistries.BLOCK_REGISTRY.all.values.forEach { block ->

			val placeholder = placeholderModelParent(block)

			val model = JsonObject().apply {

				if (placeholder != null) {

					// stands in for an unauthored model. the blockstate still points here, so replacing
					// this one file with a real cube_all + texture is the whole job.
					addProperty("parent", placeholder)

				} else {

					addProperty("parent", "minecraft:block/cube_all")
					add("textures", JsonObject().apply {
						addProperty("all", "anion:block/${block.namespacedKey.key}")

					})

				}

			}

			File(blockModelsDir, "${block.namespacedKey.key}.json").writeText(gson.toJson(model))

		}

		for (block in AnionRegistries.ITEM_REGISTRY.all.values) {

			if (block is AnionBlockItem) continue

			val model = JsonObject().apply {
				addProperty("parent", "anion:block/${block.namespacedKey.key}")
			}

			File(itemModelsDir, "${block.namespacedKey.key}.json").writeText(gson.toJson(model))

		}

	}

	/**
	 * Emits assets/minecraft/blockstates/note_block.json covering all 1350 NoteBlock variants.
	 * Registered AnionBlock states `anion:block/<key>`; skip non-anion block.
	 */
	private fun writeNoteBlockOverrides(packRoot: File) {

		val blockstatesDir = File(packRoot, "assets/minecraft/blockstates")
		blockstatesDir.mkdirs()

		// (serialized instrument name, note 0-24) -> model identifier, plus the x/y spin to apply.
		val anionStateModels = HashMap<Pair<String, Int>, Pair<String, Pair<Int, Int>>>()

		for (block in AnionRegistries.BLOCK_REGISTRY.all.values) {

			val nmsInstrument = CraftBlockData.toVanilla(block.instrument, NoteBlockInstrument::class.java)
			val model = "anion:block/${block.namespacedKey.key}"

			// a block owns however many notes its orientations need, and the spin each one wants
			for ((note, rotation) in block.stateVariants()) {
				anionStateModels[nmsInstrument.serializedName to note] = model to rotation
			}

		}

		val variants = JsonObject()
		for (instrument in NoteBlockInstrument.entries) {
			for (note in 0..24) {

				val entry = anionStateModels[instrument.serializedName to note]
				val model = entry?.first ?: continue // continue instead of writing an unnecessary entry for vanilla note blocks.
				val (rotationX, rotationY) = entry.second

				for (powered in listOf(false, true)) {

					val variantKey = "instrument=${instrument.serializedName},note=$note,powered=$powered"
					variants.add(variantKey, JsonObject().apply {
						addProperty("model", model)
						if (rotationX != 0) addProperty("x", rotationX)
						if (rotationY != 0) addProperty("y", rotationY)

					})

				}

			}

		}

		val blockstateJson = JsonObject().apply { add("variants", variants) }
		File(blockstatesDir, "note_block.json").writeText(gson.toJson(blockstateJson))

	}

}
