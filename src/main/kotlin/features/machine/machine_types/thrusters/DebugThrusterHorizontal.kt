package dev.diena.anion.features.machine.machine_types.thrusters

import dev.diena.anion.extensions.div
import dev.diena.anion.extensions.rotate
import dev.diena.anion.extensions.times
import dev.diena.anion.features.custom.blocks.AnionBlocks
import dev.diena.anion.features.machine.BlockSet
import dev.diena.anion.features.machine.Machine
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
/**
 * Debug Thruster that outputs differing levels of thrust based on the strength of the redstone signal being input.
 *
 * Also takes a throttle over a datachannel. Redstone and datachannel run alongside each other and the
 * stronger of the two wins, so wiring a mainframe to one never takes the lever away.
 */
class DebugThrusterHorizontal() : Machine("debug_thruster_horizontal", DEBUG_THRUSTER_HORIZONTAL), DcProgrammable {

	companion object {

		val RELATIVE_SMOKE_OFFSET  = Vec3i(3, 0, 0)
		val BASE_THRUSTER_VELOCITY = Vec3i(-1, 0, 0)

		/** blocks per tick the plume travels. with count 0 this is what scales the direction vector. */
		const val PLUME_SPEED = -0.9

		/** the throttle runs on the same 0-15 scale redstone does */
		const val MAX_THROTTLE = 15

	}

	override val dataInputs: List<String> = listOf("currnt_throttle", "toggled_state")
	override val dataFunctions: List<String> = listOf("increase_throttle", "decrease_throttle", "toggle", "reset")

	/** throttle set over the datachannel, stepped one notch per tick a step function is held */
	private var throttle = 0

	/** full burn, mirroring whatever latched the call. bypasses the throttle while it is on. */
	private var toggled = false

	override fun invoke(function: String, active: Boolean) {

		when (function) {

			"increase_throttle" -> if (active) throttle = (throttle + 1).coerceAtMost(MAX_THROTTLE)
			"decrease_throttle" -> if (active) throttle = (throttle - 1).coerceAtLeast(0)

			// the latch lives on the calling side, so this just follows it
			"toggle" -> toggled = active

			"reset" -> if (!active) return else {

				throttle = 0
				toggled = false

			}

		}

		markDirty()

	}

	/** What the datachannel is asking for, on redstone's scale. */
	private fun dataThrottle(): Int = if (toggled) MAX_THROTTLE else throttle

	override fun saveState(tag: CompoundTag) {

		super.saveState(tag)

		tag.putInt("throttle", throttle)
		tag.putBoolean("toggled", toggled)

	}

	override fun loadState(tag: CompoundTag) {

		super.loadState(tag)

		throttle = tag.getIntOr("throttle", 0)
		toggled = tag.getBooleanOr("toggled", false)

	}

	override fun tick() {

		// FIXME: make this detect from any not just first
		print("${this.portWorldCells().firstOrNull()}")
		val vec = this.portWorldCells().firstOrNull() ?: return // early return if there are no dataports (it can only be dataport because of machine structure)

		val power = maxOf(level.world.getBlockAt(vec.x, vec.y, vec.z).blockPower, dataThrottle())

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
