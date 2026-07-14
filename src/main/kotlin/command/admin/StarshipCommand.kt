package dev.diena.anion.command.admin

import dev.astralchroma.processor.annotations.Command
import dev.astralchroma.processor.annotations.Name
import dev.astralchroma.processor.annotations.Sender
import dev.astralchroma.processor.annotations.Subcommand
import dev.diena.anion.Anion
import dev.diena.anion.Tasks
import dev.diena.anion.data.database.AnionPersistence
import dev.diena.anion.extensions.blockPos
import dev.diena.anion.extensions.vec3i
import dev.diena.anion.features.starship.Starship
import net.minecraft.core.Vec3i
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import kotlin.collections.ArrayDeque
import kotlin.collections.MutableMap
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.isNotEmpty
import kotlin.collections.iterator
import kotlin.collections.map
import kotlin.collections.mutableMapOf
import kotlin.collections.mutableSetOf
import kotlin.collections.set
import kotlin.collections.toSet


// TODO: add permission nodes
// TODO: add better cache per-player (PLAYER DATA)
@Command
@Name("starship")
object StarshipCommand {

    @Subcommand
    fun select(

        @Sender sender: Player

    ) {

        val distanceMap: MutableMap<UUID, Int> = mutableMapOf()
        val sVec = sender.location.block.vec3i

        for ((u,s) in Starship.loadedStarships) {
            distanceMap[u] = s.origin.distSqr(sVec).toInt()
        }

        val smallestKey: UUID = distanceMap.minByOrNull { it.value }?.key ?: return

        sender.persistentDataContainer.set(
            NamespacedKey(Anion.NAMESPACE, "selected_starship"),
            PersistentDataType.STRING,
            smallestKey.toString()
        )

        sender.sendMessage("selected starship [${smallestKey.toString().take(5)}...] at ${Starship.loadedStarships[smallestKey]?.origin}")

    }

    // shhhh it can be messy
    private fun getSelectedStarship(
        sender: Player
    ): Starship {
        val starship = Starship.loadedStarships[UUID.fromString(sender.persistentDataContainer.get(
            NamespacedKey(Anion.NAMESPACE, "selected_starship"),
            PersistentDataType.STRING
        ))]

        if (starship == null) {
            sender.sendMessage("no starship found in selected starship entry")
            throw Exception("no starship found in selected starship entry")
        }

        return starship
    }

    @Subcommand
    fun teleport(

        @Sender sender: Player,
        x: Int,
        y: Int,
        z: Int

    ) {

        val starship = getSelectedStarship(sender)

        if (starship.teleportInWorld(Vec3i(x, y, z))) sender.sendMessage("teleported ship to $x, $y, $z")
        else sender.sendMessage("unable to teleport ship")

    }

    //@Subcommand // TODO: IMPLEMENT
    fun pilot(

        @Sender sender: Player

    ) {



    }

    //@Subcommand // TODO: IMPLEMENT
    fun unpilot(

        @Sender sender: Player

    ) {

    }

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

        val locations = visited.map { it.location.blockPos }.toSet()
        val uuid = UUID.randomUUID()
        val ship = Starship().create(locations, sender.world)
        ship.uuid = uuid
        Starship.loadedStarships[uuid] = ship
        AnionPersistence.saveStarship(uuid, ship)
        sender.sendMessage("Detected ${visited.size} blocks.")

        // select ship too
        select(sender)

    }

    /** remove starship from all starship related things (very helpful note i know :3) */
    @Subcommand
    fun destroy(

        @Sender sender: Player

    ) {

        val starship = getSelectedStarship(sender)

        AnionPersistence.deleteStarship(starship.uuid)
        Starship.loadedStarships.remove(starship.uuid)

        sender.sendMessage("destroyed ${starship.uuid} from loaded ships and database")

    }

    /** move ship in given direction */
    @Subcommand
    fun move(
        @Sender sender: Player,
        x: Int,
        y: Int,
        z: Int
    ) {

        getSelectedStarship(sender).move(
            Vec3i(x, y, z)
        )

    }

    @Subcommand
    fun rotate(

        @Sender sender: Player,
        rotation: Double

    ) {

        val ship = getSelectedStarship(sender)
        ship.rotate(rotation)

        sender.sendMessage("rotated starship by $rotation degrees")

    }

    /** apply and loop "velocity" */
    @Subcommand
    fun velocity(
        @Sender sender: Player,
        x: Int,
        y: Int,
        z: Int,
        tickTime: Int = 20
    ) {

        run = true
        velocityLoop(sender, x, y, z, tickTime)

        sender.sendMessage("set starship velocity to vector [$x $y $z] at $tickTime ticks per update")

    }

    private var run = false
    private fun velocityLoop(sender: Player, x: Int, y: Int, z: Int, tickTime: Int) {

        var lastTick = Anion().server.currentTick

        // FIXME: this fires seemingly thrice every time it gets called, fix
        Tasks.runAsync {
            while (run) {

                val currentTick = Anion().server.currentTick

                if (lastTick+tickTime >= currentTick) continue

                Tasks.runSync {
                    getSelectedStarship(sender).move(
                        Vec3i(x, y, z)
                    )
                }

                lastTick = currentTick

                velocityLoop(sender, x, y, z, tickTime)

            }
        }

    }

    /** stop applied velocity */
    @Subcommand
    fun stop(
        @Sender sender: Player,
    ) {

        run = false

        sender.sendMessage("stopped starship")

    }

}