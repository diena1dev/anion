package dev.diena.anion.command.admin

import dev.astralchroma.processor.annotations.Command
import dev.astralchroma.processor.annotations.Name
import dev.astralchroma.processor.annotations.Sender
import dev.astralchroma.processor.annotations.Subcommand
import dev.diena.anion.extensions.plus
import dev.diena.anion.extensions.rotate
import dev.diena.anion.extensions.vec3i
import dev.diena.anion.features.machine.AnionMachines
import dev.diena.anion.features.machine.examples.BlinkerMachine
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.minecraft.world.level.block.Rotation
import org.bukkit.Color
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.craftbukkit.block.data.CraftBlockData
import org.bukkit.entity.Player
import kotlin.collections.component1
import kotlin.collections.component2

// TODO: add permission nodes
@Command
@Name("machine")
object MachineCommand {

    /** debug: assembles a [BlinkerMachine] with its core at the block the sender is looking at */
    @Subcommand
    fun assembleBlinkerMachine(

        @Sender sender: Player

    ) {

        val target = sender.getTargetBlockExact(16) ?: run {
            sender.info("No block in range (max 16 blocks).")
            return
        }

        val level = (sender.world as CraftWorld).handle
        val origin = target.vec3i

        val machine = BlinkerMachine().assemble(level, origin)

        sender.info("Assembled ${machine::class.simpleName} at $origin. Intact: ${machine.intact}")

    }

    @Subcommand
    fun assemble(

        @Sender sender: Player

    ) {

        val target = sender.getTargetBlockExact(16) ?: run {
            sender.info("No block in range (max 16 blocks).")
            return
        }

        val level = (sender.world as CraftWorld).handle
        val origin = target.vec3i

        for (machine in AnionMachines.all) for (rotation in Rotation.entries) {

            val machine = machine.invoke()
            val blockSet = machine.blockSet ?: continue

            val matches = blockSet.blockMap.all { (offset, expectedVariants) ->

                val offsetFromOrigin = origin+offset.rotate(rotation)

                val actualNms = (level.world.getBlockAt(offsetFromOrigin.x, offsetFromOrigin.y, offsetFromOrigin.z).blockData as CraftBlockData).state
                expectedVariants.any { expected ->
                    val expectedNms = (expected.blockData as CraftBlockData).state.rotate(rotation)
                    actualNms == expectedNms
                }

            }

            if (!matches) continue

            // if we find at least one match, we go for that and then break
            machine.assemble(level, origin, rotation)
            sender.info("Assembled ${machine::class.simpleName} at $origin. Intact: ${machine.intact}")

            return // exit whole function

        }

        // this only fires if the early return isn't called
        sender.info("Unable to assemble Machine at $origin.")

    }

    private fun Player.info(message: String) {

        this.sendMessage(
            Component.text("[Machine] ").color(TextColor.color(Color.AQUA.asARGB()))
                .append(Component.text(message).color(TextColor.color(Color.WHITE.asARGB())))
        )

    }

}
