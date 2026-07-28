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

		val machineBlocks = AnionRegistries.MACHINE_TYPE_REGISTRY.getValue(AnionRegistryKey(machineKey))?.invoke()?.blockSet ?: return

		sender.sendMessage("e")

		val forward = sender.location.direction

		sender.sendMessage("$forward")

		for ((pos, variants) in machineBlocks.blockMap) {

			// multiple variants can be valid at a cell — just place the first as a representative
			sender.world
				.setBlockData((forward*2+sender.location.toVector()+Vector(pos.x, pos.y, pos.z)), variants.first().blockData)

		}

	}

}

