package features.machine.machine_types.basic_test_machine

import dev.diena.anion.features.custom.blocks.AnionBlocks
import dev.diena.anion.features.machine.BlockSet
import dev.diena.anion.features.machine.Machine
import net.minecraft.core.Vec3i
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.block.BlockType

val BASIC_TEST_MACHINE =
		BlockSet.new("basic_test_machine")
			.core('C', AnionBlocks.COPPER_MACHINE_DISPLAY)
			.assign('I', AnionBlocks.COPPER_MACHINE_CASING)

			.assign('B', AnionBlocks.COPPER_MACHINE_BUS)
			.assign('V', AnionBlocks.COPPER_MACHINE_VALVE)

			.assign('G', BlockType.WAXED_COPPER_GRATE)
			.assign('U', AnionBlocks.URANIUM_BLOCK)
			.slice(
				"III",
				"III",
				"ICI"
			)
			.slice(
				"IGI",
				"VUB",
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

	override fun tick() {
		// NO-OP
	}

	override fun slowTick() {

		val locationOffest = Location(
			this.level.world,
			relativeSmokeOffset.x.toDouble(),
			relativeSmokeOffset.y.toDouble(),
			relativeSmokeOffset.z.toDouble()
		)

		this.level.world.spawnParticle(Particle.LARGE_SMOKE, locationOffest, 0)

	}

}
