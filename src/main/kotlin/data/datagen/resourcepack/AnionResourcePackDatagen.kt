package dev.diena.anion.data.datagen.resourcepack

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import dev.diena.anion.data.registry.registries.AnionRegistries
import java.io.File

// TODO: add support for registered CustomBlocks when implemented (blockstate models)
/** Iterates through all registered AnionItems and AnionBlocks and produces a resource pack with generated model files. */
class AnionResourcePackDatagen(private val outputDir: File) {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    companion object {
        const val PACK_FORMAT = 88
    }

    fun generate() {
        val packRoot = File(outputDir, "generated/resourcepack")
        packRoot.deleteRecursively() // scary
        packRoot.mkdirs()

        writePackMeta(packRoot)
        writeItemModels(packRoot)
    }

    private fun writePackMeta(packRoot: File) {
        val meta = JsonObject().apply {
            add("pack", JsonObject().apply {
                addProperty("pack_format", PACK_FORMAT)
                addProperty("description", "Anion Resource Pack")
            })
        }
        File(packRoot, "pack.mcmeta").writeText(gson.toJson(meta))
    }

    private fun writeItemModels(packRoot: File) {
        val itemsDir = File(packRoot, "assets/anion/items")
        itemsDir.mkdirs()

        AnionRegistries.ITEM_REGISTRY.all.values.forEach { item ->
            val model = JsonObject().apply {
                add("model", JsonObject().apply {
                    addProperty("type", "minecraft:model")
                    addProperty("model", "minecraft:item/barrier")
                })
            }
            File(itemsDir, "${item.namespacedKey.key}.json").writeText(gson.toJson(model))
        }
    }

}
