package dev.diena.anion.command.admin

import dev.astralchroma.processor.annotations.Command
import dev.astralchroma.processor.annotations.Name
import dev.astralchroma.processor.annotations.Sender
import dev.astralchroma.processor.annotations.Subcommand
import dev.diena.anion.Anion
import dev.diena.anion.Tasks
import dev.diena.anion.features.starship.Starship
import net.minecraft.core.Vec3i
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import java.util.UUID

// TODO: add permission nodes
// TODO: add per-ship selection, cache per-player
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
        Starship.loadedStarships[UUID.randomUUID()] = Starship().create(locations)
        sender.sendMessage("Detected ${visited.size} blocks.")

    }

    @Subcommand
    fun move(
        @Sender sender: Player,
        x: Int,
        y: Int,
        z: Int
    ) {

        Starship.loadedStarships.entries.first().value.move(
            Vec3i(x, y, z)
        )

    }

    @Subcommand
    fun velocity(
        @Sender sender: Player,
        x: Int,
        y: Int,
        z: Int,
        tickTime: Int = 20
    ) {

        run = true
        velocityLoop(x, y, z, tickTime)

    }

    private var run = false
    private fun velocityLoop(x: Int, y: Int, z: Int, tickTime: Int) {

        var lastTick = Anion().server.currentTick

        Tasks.runAsync {
            while (run) {

                val currentTick = Anion().server.currentTick

                if (lastTick+tickTime >= currentTick) continue

                Tasks.runSync {
                    Starship.loadedStarships.entries.first().value.move(
                        Vec3i(x, y, z)
                    )
                }

                lastTick = currentTick

                velocityLoop(x, y, z, tickTime)

            }
        }

    }

    @Subcommand
    fun stop(
        @Sender sender: Player,
    ) {

        run = false

    }

}