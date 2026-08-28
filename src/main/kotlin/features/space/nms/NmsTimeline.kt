package dev.diena.anion.features.space.nms

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import net.minecraft.server.MinecraftServer
import net.minecraft.world.timeline.Timeline

/**
 * Builds [Timeline] values. Timeline's constructor is private and it has no builder, so the codec
 * is the only way in — this decodes an in-memory JSON tree, no datapack on disk.
 *
 * Shape (all fields but `clock` optional):
 * ```
 * {
 *   "clock": "minecraft:overworld",
 *   "period_ticks": 24000,
 *   "tracks": {
 *     "minecraft:visual/fog_color": { "keyframes": [ { "time": 0, "value": "#0A0A14" } ] }
 *   }
 * }
 * ```
 */
object NmsTimeline {

	/** Decodes a timeline from a JSON tree. Throws with the codec's own error on a bad shape. */
	fun build(

		json: JsonElement

	): Timeline {

		val ops = MinecraftServer.getServer().registryAccess().createSerializationContext(JsonOps.INSTANCE)

		return Timeline.DIRECT_CODEC.parse(ops, json)
			.getOrThrow { message -> IllegalArgumentException("Failed to build timeline: $message") }

	}

	/** Decodes a timeline from a JSON string. */
	fun build(

		json: String

	): Timeline = build(JsonParser.parseString(json))

	/** Re-encodes [timeline] back to JSON, for round-tripping or debug output. */
	fun encode(

		timeline: Timeline

	): JsonElement {

		val ops = MinecraftServer.getServer().registryAccess().createSerializationContext(JsonOps.INSTANCE)

		return Timeline.DIRECT_CODEC.encodeStart(ops, timeline)
			.getOrThrow { message -> IllegalArgumentException("Failed to encode timeline: $message") }

	}

}
