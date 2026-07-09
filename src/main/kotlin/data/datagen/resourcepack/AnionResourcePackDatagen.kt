package dev.diena.anion.data.datagen.resourcepack

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import dev.diena.anion.data.registry.registries.AnionRegistries
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument
import org.bukkit.craftbukkit.block.data.CraftBlockData
import java.io.File
import java.util.Base64

// FIXME: un-jank this so it consistently generates clean resource packs.
/** Iterates all registered AnionItems and AnionBlocks and produces a resource pack with generated model files. */
class AnionResourcePackDatagen(private val outputDir: File) {

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    private val barrierTextureB64 = "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAXElEQVR42q2TyQ0AIAgEsf9qbMBmLMTjReLBEIVkf8zsByR0SkqtipiZO0dYlzh5ZG3GhjlXCcDrzlUAsBYYAoZBADAJAGYBwCwAmAVeWAX/h6STH095k3gS+sEdGgfXopZkuo0AAAAASUVORK5CYII="

    companion object {
        const val PACK_FORMAT = 88
    }

    fun generate() {
        val packRoot = File(outputDir, "generated/resourcepack")
        packRoot.deleteRecursively()
        packRoot.mkdirs()

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

    private fun writeTextures(packRoot: File) {
        val itemTexturesDir = File(packRoot, "assets/anion/textures/item")
        val blockTexturesDir = File(packRoot, "assets/anion/textures/block")
        itemTexturesDir.mkdirs()
        blockTexturesDir.mkdirs()

        val pngBytes = Base64.getDecoder().decode(barrierTextureB64)

        // FIXME: generates textures for registered block items along with normal items
        AnionRegistries.ITEM_REGISTRY.all.values.forEach { item ->

            val key = item.namespacedKey.key

            File(itemTexturesDir, "$key.png").writeBytes(pngBytes)
        }

        // gen textures for blocks
        AnionRegistries.BLOCK_REGISTRY.all.values.forEach { block ->

            val key = block.namespacedKey.key

            File(itemTexturesDir, "$key.png").writeBytes(pngBytes)
        }
    }

    private fun writeBlockAndBlockItemModels(packRoot: File) {
        val blockModelsDir = File(packRoot, "assets/anion/models/block")
        val itemModelsDir = File(packRoot, "assets/anion/models/item")
        blockModelsDir.mkdirs()
        itemModelsDir.mkdirs()

        AnionRegistries.BLOCK_REGISTRY.all.values.forEach { block ->
            val model = JsonObject().apply {
                addProperty("parent", "minecraft:block/cube_all")
                add("textures", JsonObject().apply {
                    addProperty("all", "anion:block/${block.namespacedKey.key}")
                })
            }
            File(blockModelsDir, "${block.namespacedKey.key}.json").writeText(gson.toJson(model))
        }

        AnionRegistries.BLOCK_REGISTRY.all.values.forEach { block ->
            val model = JsonObject().apply {
                addProperty("parent", "anion:block/${block.namespacedKey.key}")
            }
            File(itemModelsDir, "${block.namespacedKey.key}.json").writeText(gson.toJson(model))
        }
    }

    /**
     * Emits assets/minecraft/blockstates/note_block.json covering all 1350 NoteBlock variants.
     * Registered AnionBlock states → anion:block/<key>; all others → minecraft:block/note_block.
     */
    private fun writeNoteBlockOverrides(packRoot: File) {
        val blockstatesDir = File(packRoot, "assets/minecraft/blockstates")
        blockstatesDir.mkdirs()

        // (serialized instrument name, note 0-24) model identifier
        val anionStateModels: Map<Pair<String, Int>, String> = AnionRegistries.BLOCK_REGISTRY.all.values.associate { block ->
            val nmsInstrument = CraftBlockData.toVanilla(block.instrument, NoteBlockInstrument::class.java)
            (nmsInstrument.serializedName to block.note) to "anion:block/${block.namespacedKey.key}"
        }

        val variants = JsonObject()
        for (instrument in NoteBlockInstrument.entries) {
            for (note in 0..24) {
                val model = anionStateModels[instrument.serializedName to note] ?: "minecraft:block/note_block"
                for (powered in listOf(false, true)) {
                    val variantKey = "instrument=${instrument.serializedName},note=$note,powered=$powered"
                    variants.add(variantKey, JsonObject().apply { addProperty("model", model) })
                }
            }
        }

        val blockstateJson = JsonObject().apply { add("variants", variants) }
        File(blockstatesDir, "note_block.json").writeText(gson.toJson(blockstateJson))
    }

}
