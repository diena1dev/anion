package dev.diena.anion.command.admin

import dev.astralchroma.processor.annotations.Command
import dev.astralchroma.processor.annotations.Inferred
import dev.astralchroma.processor.annotations.Name
import dev.astralchroma.processor.annotations.Sender
import dev.diena.anion.data.registry.AnionRegistryKey
import dev.diena.anion.data.registry.registries.AnionRegistries
import dev.diena.anion.extensions.plus
import dev.diena.anion.extensions.times
import org.bukkit.entity.Player
import org.bukkit.util.Vector

@Command
@Name("placemachine")
object PlaceMachineStructure {

	@Inferred
	fun self(

		@Sender sender: Player,
		machineKey: String

	) {

		val machineStructure = AnionRegistries.MACHINE_STRUCTURE_REGISTRY.getValue(AnionRegistryKey(machineKey)) ?: return

		sender.sendMessage("e")

		val forward = sender.location.direction
		val actualForward = forward.normalize()*2

		sender.sendMessage("$forward")

		for ((pos, block) in machineStructure.blockMap) {

			sender.world
				.setBlockData((forward*2+sender.location.toVector()+Vector(pos.x, pos.y, pos.z)), block.blockData)

		}

	}

}
