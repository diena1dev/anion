package features.machine.machine_types.basic_test_machine

import dev.diena.anion.extensions.plus
import dev.diena.anion.extensions.rotateAround
import dev.diena.anion.features.custom.blocks.AnionBlock
import dev.diena.anion.features.custom.blocks.AnionBlocks
import dev.diena.anion.features.machine.BlockSet
import dev.diena.anion.features.machine.Machine
import net.minecraft.core.Vec3i
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.block.BlockType

val BASIC_TEST_MACHINE =
		BlockSet.new("basic_test_machine")
			.core('C', AnionBlocks.TEST_BLOCK)

			.assign('I', AnionBlocks.COPPER_MACHINE_CASING)
			.assign('I', AnionBlocks.COPPER_MACHINE_BUS)
			.assign('I', AnionBlocks.COPPER_MACHINE_VALVE)
			.assign('I', AnionBlocks.COPPER_MACHINE_DISPLAY)
			.assign('I', AnionBlocks.COPPER_MACHINE_DATAPORT)

			.assign('G', BlockType.WAXED_COPPER_GRATE)
			.assign('U', AnionBlocks.URANIUM_BLOCK)
			.slice(
				"III",
				"III",
				"ICI"
			)
			.slice(
				"IGI",
				"IUI",
				"IGI"
			)
			.slice(
				"IGI",
				"IUI",
				"IGI"
			)
			.slice(
				"III",
				"III",
				"III"
			)

			.build()

class BasicTestMachine : Machine("Basic Test Machine", BASIC_TEST_MACHINE) {

	val relativeSmokeOffset = Vec3i(0, 5, 0)

	lateinit var locationOffset: Location

	override fun tick() {
		Particle.CAMPFIRE_SIGNAL_SMOKE.builder()
			.location(this.locationOffset)
			.offset(0.0, 1.5, 0.0)
			.count(0)
			.extra(0.1)
			.spawn()
	}

	override fun slowTick() {
		// NO-OP
	}

	override fun onAssemble() {
		val offset = relativeSmokeOffset+this.origin

		this.locationOffset = Location(
			this.level.world,
			offset.x.toDouble()-0.5,
			offset.y.toDouble(),
			offset.z.toDouble()+0.5,
		).rotateAround(this.origin, this.rotation) // FIXME: this is broken!

		super.onAssemble()
	}

}
