package dev.diena.anion.command.admin

import dev.astralchroma.processor.annotations.Command
import dev.astralchroma.processor.annotations.Name
import dev.astralchroma.processor.annotations.Sender
import dev.astralchroma.processor.annotations.Subcommand
import dev.diena.anion.Anion
import dev.diena.anion.features.starship.Starship
import net.minecraft.core.Direction
import net.minecraft.core.Vec3i
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import java.util.UUID

// TODO: add permission nodes
@Command
@Name("starship")
object StarshipCommand {

    @Subcommand
    fun detect(

        @Sender sender: Player

    ) {

        val origin = sender.location.block
        val visited = mutableSetOf<Block>()
        val queue = ArrayDeque<Block>()

        queue.add(origin)
        visited.add(origin)

        val cardinalFaces = arrayOf(
            BlockFace.UP,
            BlockFace.DOWN,
            BlockFace.NORTH,
            BlockFace.SOUTH,
            BlockFace.EAST,
            BlockFace.WEST
        )

        while (queue.isNotEmpty()) {
            if (visited.size > 50_000) {
                sender.sendMessage("don't stand on terrain, detection exceeded 50,000 blocks")
                return
            }

            val current = queue.removeFirst()

            for (face in cardinalFaces) {
                val neighbor = current.getRelative(face)
                if (neighbor !in visited && !neighbor.type.isAir) {
                    visited.add(neighbor)
                    queue.add(neighbor)
                }
            }
        }

        val locations = visited.map { it.location }.toSet()
        Anion.TEMPORARY_ACTIVE_STARSHIPS_MAP[UUID.randomUUID()] = Starship().create(locations)
        sender.sendMessage("Detected ${visited.size} blocks.")

    }

    @Subcommand
    fun move(
        @Sender sender: Player,
        x: Int,
        y: Int,
        z: Int
    ) {

        Anion.TEMPORARY_ACTIVE_STARSHIPS_MAP.entries.first().value.move(
            Vec3i(x, y, z)
        )

    }

}