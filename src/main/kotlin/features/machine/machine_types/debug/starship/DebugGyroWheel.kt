package dev.diena.anion.features.machine.machine_types.debug.transport

import dev.diena.anion.features.custom.blocks.AnionBlocks
import dev.diena.anion.features.machine.BlockSet
import dev.diena.anion.features.machine.Machine
import dev.diena.anion.features.machine.component.MachinePort
import dev.diena.anion.features.scripting.DcProgrammable
import dev.diena.anion.features.scripting.DcType
import net.minecraft.core.Vec3i
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.block.BlockType

/**
 * A casing base with dataports on both flanks and a ring of bars stood on top of it — the wheel that
 * actually spins is scenery, the same way a thruster's nozzle is.
 */
val DEBUG_GYRO_WHEEL = BlockSet.new("debug_gyro_wheel")

	.core('C', BlockType.LODESTONE)

	.assign('c', AnionBlocks.COPPER_MACHINE_CASING)
	.assign('c', AnionBlocks.COPPER_MACHINE_DATAPORT)
	.assign('b', BlockType.IRON_BARS)
	.assign('w', BlockType.POLISHED_DEEPSLATE_WALL)

	.slice(
		" c ",
		"ccc",
		" c ",

	)
	.slice(
		"   ",
		" w ",
		"   "
	)
	.slice(
		"bbb",
		"bCb",
		"bbb"
	)

	.build()

/**
 * Debug machine that turns the ship carrying it.
 */
class DebugGyroWheel() : Machine("debug_gyro_wheel", DEBUG_GYRO_WHEEL), DcProgrammable {

	companion object {

		/** degrees a held function turns the ship each tick. 90 of these is a quarter turn. */
		const val DEGREES_PER_TICK = 5.0

		/** degrees a snap puts in at once, on the press rather than per tick */
		const val SNAP_DEGREES = 45.0

		/** yaw rises clockwise seen from above, so starboard is the positive direction */
		const val STARBOARD = 1.0
		const val PORT = -1.0

	}

	override val dataInputs: Map<String, DcType> =
		mapOf("currnt_yaw" to DcType.NUM, "spinning_state" to DcType.NUM)
	override val dataFunctions: List<String> =
		listOf("rotate_left", "rotate_right", "snap_left", "snap_right")

	/** held functions, read every tick for as long as they are on */
	private var turningPort = false
	private var turningStarboard = false

	// snaps fire on the press, so each one remembers whether it was already down. holding one otherwise
	// spins the ship 45 degrees a tick, which is eleven turns a second.
	private var snapPortHeld = false
	private var snapStarboardHeld = false

	/** degrees a snap has queued for the next tick, applied and cleared there */
	private var queuedSnap = 0.0

	/** whether the last attempt was refused, for the debug readout */
	private var blocked = false

	override fun invoke(function: String, active: Boolean) {

		when (function) {

			"rotate_left" -> turningPort = active
			"rotate_right" -> turningStarboard = active

			"snap_left" -> {

				if (active && !snapPortHeld) queuedSnap += SNAP_DEGREES * PORT
				snapPortHeld = active

			}

			"snap_right" -> {

				if (active && !snapStarboardHeld) queuedSnap += SNAP_DEGREES * STARBOARD
				snapStarboardHeld = active

			}

		}

	}

	override fun tick() {

		var degrees = queuedSnap
		queuedSnap = 0.0

		if (turningPort) degrees += DEGREES_PER_TICK * PORT
		if (turningStarboard) degrees += DEGREES_PER_TICK * STARBOARD

		if (degrees == 0.0) return

		// grounded: nothing to turn, and a queued snap is dropped rather than saved up for a ship that
		// might be built around this later
		val ship = this.starship ?: return

		blocked = !ship.rotate(degrees)
		if (blocked) return

		emitSpin()

	}

	override fun slowTick() {
		// no-op
	}

	override fun debugLines(): List<String> {

		val spin = when {

			turningPort && turningStarboard -> "both ways, so neither"
			turningPort -> "port at $DEGREES_PER_TICK deg/tick"
			turningStarboard -> "starboard at $DEGREES_PER_TICK deg/tick"

			else -> "idle"

		}

		val yaw = starship?.yaw?.let { "%.1f".format(it) } ?: "no carrier"

		// a gyro with no dataport is unreachable, and every other line here looks perfectly healthy
		val dataports = portWorldCells(MachinePort.Kind.DATA).size

		return listOf(
			"spin: $spin",
			"ship yaw: $yaw",
			"last rotation blocked: $blocked",
			"dataports: $dataports${if (dataports == 0) " | mainframe disconnection" else ""}",
		)

	}

	/** a puff around the wheel, so a ship creeping round a degree at a time still looks like it is working */
	// the core sits in the middle of the ring, so the ring's centre is the core's own cell
	private fun emitSpin() {

		val wheel = localToWorld(Vec3i.ZERO)

		Particle.ELECTRIC_SPARK.builder()
			.location(Location(level.world, wheel.x + 0.5, wheel.y + 0.5, wheel.z + 0.5))
			.offset(0.2, 0.05, 0.2)
			.count(2)
			.spawn()

	}

}
