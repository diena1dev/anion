package dev.diena.anion.features.machine.machine_types.debug.transport

import dev.diena.anion.extensions.div
import dev.diena.anion.extensions.rotate
import dev.diena.anion.extensions.times
import dev.diena.anion.features.custom.blocks.AnionBlocks
import dev.diena.anion.features.machine.BlockSet
import dev.diena.anion.features.machine.Machine
import dev.diena.anion.features.machine.machine_types.thrusters.ThrusterThrottle
import dev.diena.anion.features.scripting.DcProgrammable
import net.minecraft.core.Vec3i
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.phys.Vec3
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.block.BlockType

val DEBUG_THRUSTER_HORIZONTAL = BlockSet.new("debug_thruster_horizontal")

	.core('C', BlockType.IRON_BLOCK)

	.assign('I', AnionBlocks.COPPER_MACHINE_CASING)
	.assign('I', AnionBlocks.COPPER_MACHINE_DATAPORT) // used to detect redstone input

	.assign('D', BlockType.POLISHED_DEEPSLATE_STAIRS)
	.assign('d', BlockType.DIORITE_STAIRS)
	.assign('i', BlockType.IRON_BLOCK)

	.slice(
		"II",
		"II",
		"Ci",
		"dd",
		"DD"
	)
	.slice(
		"II",
		"II",
		"ii",
		"dd",
		"DD"
	)

	.build()

// TODO: make generalized thruster class
/** Debug Thruster that moves the starship it's attached to. if no ship is attached, it emits smoke. */
class DebugThrusterHorizontal() : Machine("debug_thruster_horizontal", DEBUG_THRUSTER_HORIZONTAL), DcProgrammable {

	companion object {

		val RELATIVE_SMOKE_OFFSET  = Vec3i(3, 0, 0)
		val BASE_THRUSTER_VELOCITY = Vec3i(-1, 0, 0)

		/** blocks per tick the plume travels. with count 0 this is what scales the direction vector. */
		const val PLUME_SPEED = -0.9

	}

	private val controls = ThrusterThrottle.Companion.new()

	// no hover: this one pushes sideways, so there is no altitude for it to hold
	override val dataInputs: List<String> = listOf("currnt_throttle", "toggled_state")
	override val dataFunctions: List<String> = ThrusterThrottle.Companion.FUNCTIONS

	override fun invoke(function: String, active: Boolean) {

		if (!controls.invoke(function, active)) return

		markDirty()

	}

	override fun debugLines(): List<String> = listOf(controls.describe())

	override fun saveState(tag: CompoundTag) {

		super.saveState(tag)

		controls.saveTo(tag)

	}

	override fun loadState(tag: CompoundTag) {

		super.loadState(tag)

		controls.loadFrom(tag)

	}

	override fun tick() {

		// FIXME: make this detect from any not just first
		print("${this.portWorldCells().firstOrNull()}")
		val vec = this.portWorldCells().firstOrNull() ?: return // early return if there are no dataports (it can only be dataport because of machine structure)

		val power = maxOf(level.world.getBlockAt(vec.x, vec.y, vec.z).blockPower, controls.power())

		if (power > 0) {

			emitSmoke()

			val ship = this.starship ?: return
			val proportionateVelocity = getDirectionalVelocity(power)
			ship.velocity.addVelocity(proportionateVelocity)

		}

	}

	override fun slowTick() {
		// no-op
	}

	/** Returns a [Vec3] that respects the rotation of the thruster. */
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

		return

	}

}
