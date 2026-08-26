package dev.diena.anion.features.machine.machine_types.debug.transport

import dev.diena.anion.extensions.div
import dev.diena.anion.extensions.rotate
import dev.diena.anion.extensions.times
import dev.diena.anion.features.custom.blocks.AnionBlocks
import dev.diena.anion.features.machine.BlockSet
import dev.diena.anion.features.machine.Machine
import dev.diena.anion.features.machine.component.MachinePort
import dev.diena.anion.features.machine.machine_types.thrusters.ThrusterHover
import dev.diena.anion.features.machine.machine_types.thrusters.ThrusterThrottle
import dev.diena.anion.features.scripting.DcProgrammable
import net.minecraft.core.Vec3i
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.phys.Vec3
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.block.BlockType
import kotlin.collections.plus

val DEBUG_THRUSTER_DOWN = BlockSet.new("debug_thruster_down")

	.core('C', BlockType.IRON_BLOCK)

	.assign('I', AnionBlocks.COPPER_MACHINE_CASING)
	.assign('I', AnionBlocks.COPPER_MACHINE_DATAPORT) // used to detect redstone input

	.assign('D', BlockType.POLISHED_DEEPSLATE_STAIRS)
	.assign('d', BlockType.DIORITE_WALL)
	.assign('i', BlockType.IRON_BLOCK)

	.slice(
		"II",
		"II"
	)
	.slice(
		"II",
		"II"
	)
	.slice(
		"Ci",
		"ii"
	)
	.slice(
		"dd",
		"dd"
	)
	.slice(
		"DD",
		"DD"
	)

	.build()

// TODO: make generalized thruster class
/** Debug Thruster that moves the starship it's attached to. if no ship is attached, it emits smoke. */
class DebugThrusterDown() : Machine("debug_thruster_down", DEBUG_THRUSTER_DOWN), DcProgrammable {

	companion object {

		val RELATIVE_SMOKE_OFFSET  = Vec3i(0, 3, 0)
		val BASE_THRUSTER_VELOCITY = Vec3i(0, -1, 0)

		/** blocks per tick the plume travels. with count 0 this is what scales the direction vector. */
		const val PLUME_SPEED = -0.9

		/** this one pushes down, so its pin only ever falls */
		const val THRUST_SIGN = -1

	}

	private val controls = ThrusterThrottle.Companion.new()
	private val hover = ThrusterHover.Companion.new(this, THRUST_SIGN)

	override val dataInputs: List<String> = listOf("currnt_throttle", "toggled_state", "hover_state")
	override val dataFunctions: List<String> = ThrusterThrottle.Companion.FUNCTIONS + ThrusterHover.Companion.FUNCTION

	override fun invoke(function: String, active: Boolean) {

		if (function == ThrusterHover.Companion.FUNCTION) hover.engage(active)
		else if (!controls.invoke(function, active)) return

		markDirty()

	}

	override fun tick() {

		val thrust = maxOf(portSignal(MachinePort.Kind.DATA), controls.power())
		val holding = hover.engaged

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
