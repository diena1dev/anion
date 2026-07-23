package dev.diena.anion.command.admin

import dev.astralchroma.processor.annotations.Command
import dev.astralchroma.processor.annotations.Name
import dev.astralchroma.processor.annotations.Sender
import dev.astralchroma.processor.annotations.Subcommand
import dev.diena.anion.Anion
import dev.diena.anion.data.database.AnionPersistence
import dev.diena.anion.extensions.blockPos
import dev.diena.anion.extensions.vec3i
import dev.diena.anion.features.starship.Starship
import net.kyori.adventure.text.Component
import net.minecraft.core.Vec3i
import net.kyori.adventure.text.format.TextColor
import net.minecraft.world.phys.Vec3
import org.bukkit.Color
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

        sender.info("Selected starship [${smallestKey.toString().take(5)}...] at ${Starship.loadedStarships[smallestKey]?.origin}.")

    }

    @Subcommand
    fun teleport(

        @Sender sender: Player,
        x: Int,
        y: Int,
        z: Int,
        preserveVelocity: Boolean = true,

    ) {

        val starship = getSelectedStarship(sender)

        if (starship.teleportInWorld(Vec3i(x, y, z), preserveVelocity)) sender.sendMessage("teleported ship to $x, $y, $z")
        else sender.sendMessage("unable to teleport ship")

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

        sender.info("Detected ${visited.size} blocks and created starship at ${ship.origin}.")

        // select ship too
        select(sender)

    }

    /** remove starship from all starship related things (very helpful note i know :3) */
    @Subcommand
    fun remove(

        @Sender sender: Player,
        confirm: Boolean = false,

    ) {

        if (!confirm) {

            sender.info("WARNING: THIS WILL RENDER YOUR SHIP UNABLE TO MOVE!\n           To confirm, add 'true' to the end of this command.")
            return

        }

        val starship = getSelectedStarship(sender)

        AnionPersistence.deleteStarship(starship.uuid)
        Starship.loadedStarships.remove(starship.uuid)

        sender.info("Removed ship from tick list and dropped from database.")

    }

    /** move ship in given direction */
    @Subcommand
    fun move(

        @Sender sender: Player,
        x: Int,
        y: Int,
        z: Int

    ) {

        val moveResult = getSelectedStarship(sender).move(
            Vec3i(x, y, z)
        )

        if (moveResult) return
        sender.info("Failed to move starship by provided Vec3i($x, $y, $z)!")

    }

    @Subcommand
    fun rotate(

        @Sender sender: Player,
        rotation: Double

    ) {

        val ship = getSelectedStarship(sender)
        ship.rotate(rotation)

        sender.info("Applied $rotation degrees to starship yaw. Current Yaw: ${ship.yaw}")

    }

    /** modify starship velocity */
    @Subcommand
    object Velocity {

        @Subcommand
        fun add(

            @Sender sender: Player,
            x: Double,
            y: Double,
            z: Double,

        ) {

            val ship = getSelectedStarship(sender)
            val newVelocity = ship.velocity.addVelocity(Vec3(x, y, z))

            sender.info("Added Vec($x, $y, $z) to Velocity. Current Velocity: $newVelocity.")

        }

        @Subcommand
        fun reset(

            @Sender sender: Player

        ) {

            val ship = getSelectedStarship(sender)
            ship.velocity.resetVelocity()

            sender.info("Reset Velocity to Vec(0.0, 0.0, 0.0).")

        }

    }

    ///// HELPER FUNCTIONS

    private fun Player.info(message: String) {

        this.sendMessage(
            Component.text("[Starship] ").color(TextColor.color(Color.AQUA.asARGB()))
                .append(Component.text(message))
        )

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

}
