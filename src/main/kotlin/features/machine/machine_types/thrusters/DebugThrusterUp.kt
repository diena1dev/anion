package dev.diena.anion.features.machine.machine_types.thrusters

import dev.diena.anion.extensions.div
import dev.diena.anion.extensions.rotate
import dev.diena.anion.extensions.times
import dev.diena.anion.features.custom.blocks.AnionBlocks
import dev.diena.anion.features.machine.BlockSet
import dev.diena.anion.features.machine.Machine
import dev.diena.anion.features.machine.component.MachinePort
import dev.diena.anion.features.scripting.DcProgrammable
import net.minecraft.core.Vec3i
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.phys.Vec3
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.block.BlockType

/**
 * The horizontal thruster stood on end. Rows run along x, characters along z, and each slice() is one
 * course up — so the barrel that ran along x there runs along y here, one cell per slice.
 *
 * Nozzle at the bottom: this one pushes a ship up, so its exhaust leaves downward.
 */
val DEBUG_THRUSTER_UP = BlockSet.new("debug_thruster_up")

	.core('C', BlockType.IRON_BLOCK)

	.assign('I', AnionBlocks.COPPER_MACHINE_CASING)
	.assign('I', AnionBlocks.COPPER_MACHINE_DATAPORT) // used to detect redstone input
	.assign('I', AnionBlocks.COPPER_MACHINE_CONDUIT)  // powered, holds the ship at its current height

	.assign('D', BlockType.POLISHED_DEEPSLATE_STAIRS)
	.assign('d', BlockType.DIORITE_WALL)
	.assign('i', BlockType.IRON_BLOCK)

	.slice(
		"DD",
		"DD"
	)
	.slice(
		"dd",
		"dd"
	)
	.slice(
		"Ci",
		"ii"
	)
	.slice(
		"II",
		"II"
	)
	.slice(
		"II",
		"II"
	)

	.build()

// TODO: make generalized thruster class — three copies of this now, which is two more than the
//       duplication was worth when there was only the horizontal one.
/**
 * Debug Thruster that outputs differing levels of thrust based on the strength of the redstone signal
 * being input.
 *
 * A powered conduit pins the ship to the highest altitude it has reached since the conduit came on,
 * arresting downward motion at that altitude and climbing back to it after any sag. Its authority is
 * finite — a fast fall is bled off over several ticks rather than stopped dead.
 *
 * A powered dataport thrusts as normal and takes precedence on its own: the pin only ever rises, so
 * thrusting drags it up and releasing leaves the ship held wherever it coasted to.
 *
 * Datachannel throttle and hover run alongside both, and the stronger of redstone and datachannel wins —
 * wiring a mainframe to one never takes the lever away.
 */
class DebugThrusterUp() : Machine("debug_thruster_up", DEBUG_THRUSTER_UP), DcProgrammable {

	companion object {

		val RELATIVE_SMOKE_OFFSET  = Vec3i(0, -3, 0)
		val BASE_THRUSTER_VELOCITY = Vec3i(0, 1, 0)

		/** blocks per tick the plume travels. with count 0 this is what scales the direction vector. */
		const val PLUME_SPEED = -0.9

		/** this one pushes up, so its pin only ever rises */
		const val THRUST_SIGN = 1

	}

	private val controls = ThrusterThrottle.new()
	private val hover = ThrusterHover.new(this, THRUST_SIGN)

	override val dataInputs: List<String> = listOf("currnt_throttle", "toggled_state", "hover_state")
	override val dataFunctions: List<String> = ThrusterThrottle.FUNCTIONS + ThrusterHover.FUNCTION

	override fun invoke(function: String, active: Boolean) {

		if (function == ThrusterHover.FUNCTION) hover.engage(active)
		else if (!controls.invoke(function, active)) return

		markDirty()

	}

	override fun tick() {

		val thrust = maxOf(portSignal(MachinePort.Kind.DATA), controls.power())
		val holding = portSignal(MachinePort.Kind.CONDUIT) > 0 || hover.engaged

		if (!holding) hover.release()

		if (thrust <= 0 && !holding) return

		emitSmoke()

		val ship = this.starship ?: return

		// before the thrust below, so a hold never eats the velocity that same thrust just added
		if (holding) hover.hold(ship)

		if (thrust > 0) ship.velocity.addVelocity(getDirectionalVelocity(thrust))

	}

	override fun slowTick() {
		// no-op
	}

	override fun debugLines(): List<String> = listOf(controls.describe(), hover.describe())

	override fun saveState(tag: CompoundTag) {

		super.saveState(tag)

		controls.saveTo(tag)
		hover.saveTo(tag)

	}

	override fun loadState(tag: CompoundTag) {

		super.loadState(tag)

		controls.loadFrom(tag)
		hover.loadFrom(tag)

	}

	/** Returns a [Vec3] that respects the rotation of the thruster. */
	// a ship only ever turns about y, so a thruster pointing along y is the same after every rotation.
	// rotate() is kept anyway so this reads identically to the horizontal one.
	private fun getDirectionalVelocity(redstonePower: Int): Vec3 {

		val direction = BASE_THRUSTER_VELOCITY.rotate(rotation)
		return Vec3(direction.x.toDouble(), direction.y.toDouble(), direction.z.toDouble())
			.div(120) // make smol
			.times(Vec3(redstonePower.toDouble(), redstonePower.toDouble(), redstonePower.toDouble())) // make beeg again

	}

	/** emits smoke in the direction the thruster cone is facing */
	private fun emitSmoke() {

		val nozzle = localToWorld(RELATIVE_SMOKE_OFFSET)
		val direction = BASE_THRUSTER_VELOCITY.rotate(rotation)

		val nozzleCentre = Location(
			this.level.world,
			nozzle.x + 0.5,
			nozzle.y + 0.5,
			nozzle.z + 0.5,
		)

		Particle.CAMPFIRE_COSY_SMOKE.builder()
			.location(nozzleCentre)
			.offset(direction.x.toDouble(), direction.y.toDouble(), direction.z.toDouble())
			.count(0)
			.extra(PLUME_SPEED)
			.spawn()

	}

}
