package dev.diena.anion.features.space.validation.checks

import dev.diena.anion.features.space.nms.NmsDimensionType
import dev.diena.anion.features.space.nms.NmsRegistry
import dev.diena.anion.features.space.nms.NmsTimeline
import dev.diena.anion.features.space.validation.CheckGroup
import dev.diena.anion.features.space.validation.Validation

/**
 * Timelines, which only exist through their codec. These drive the day/night attribute tracks a
 * planet's sky animates over, so a broken decode is a static sky.
 */
object NmsTimelineChecks {

	private const val MINIMAL = """{ "clock": "minecraft:overworld" }"""

	private const val WITH_FOG_TRACK = """
		{
		  "clock": "minecraft:overworld",
		  "period_ticks": 24000,
		  "tracks": {
			"minecraft:visual/fog_color": {
			  "keyframes": [
				{ "ticks": 0, "value": "#0A0A14" },
				{ "ticks": 12000, "value": "#141428" }
			  ]
			}
		  }
		}
	"""

	val group = CheckGroup(

		name = "nms-timeline",
		description = "timeline decoding, encoding and registration",
		destructive = true,

	).apply {

		check("a minimal timeline decodes") {

			val timeline = NmsTimeline.build(MINIMAL)
			requireNotNull(timeline, "decoded timeline")

		}

		check("a timeline with an attribute track decodes") {

			// if the track shape is wrong this throws with the codec's own message, which is the
			// fastest way to learn the real json shape for a given attribute
			val timeline = NmsTimeline.build(WITH_FOG_TRACK)
			requireNotNull(timeline, "decoded timeline with tracks")

		}

		check("malformed json is rejected") {

			requireThrows("timeline with no clock") { NmsTimeline.build("""{ "period_ticks": 100 }""") }

		}

		check("an unknown clock is rejected") {

			requireThrows("timeline referencing a missing clock") {
				NmsTimeline.build("""{ "clock": "anion:no_such_clock" }""")
			}

		}

		check("encode round trips back to a decodable tree") {

			val timeline = NmsTimeline.build(WITH_FOG_TRACK)
			val encoded = NmsTimeline.encode(timeline)

			requireNotNull(NmsTimeline.build(encoded), "timeline decoded from its own encoding")

		}

		check("a timeline registers and reads back") {

			val identifier = Validation.scratchIdentifier("timeline")
			val holder = NmsRegistry.setTimeline(identifier, NmsTimeline.build(MINIMAL))

			require(holder.isBound, "timeline holder not bound")
			requireNotNull(NmsRegistry.getTimeline(identifier), "timeline read back")

		}

		check("a registered timeline resolves through timelinesOf") {

			val identifier = Validation.scratchIdentifier("timelineset")
			NmsRegistry.setTimeline(identifier, NmsTimeline.build(MINIMAL))

			val holderSet = NmsDimensionType.timelinesOf(identifier)
			requireEquals(1, holderSet.size(), "holder set size")

		}

		check("a dimension type accepts a registered timeline") {

			val identifier = Validation.scratchIdentifier("timelinedim")
			NmsRegistry.setTimeline(identifier, NmsTimeline.build(MINIMAL))

			val dimensionType = NmsDimensionType.build(
				minY = 0,
				height = 256,
				timelines = NmsDimensionType.timelinesOf(identifier),
			)

			requireEquals(1, dimensionType.timelines().size(), "timelines on the dimension type")

		}

	}

}
