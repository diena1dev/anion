package dev.diena.anion.features.custom.items

import dev.diena.anion.Tasks
import dev.diena.anion.extensions.floorVec3i
import dev.diena.anion.extensions.plus
import dev.diena.anion.extensions.times
import dev.diena.anion.extensions.vec3i
import dev.diena.anion.features.starship.Starship
import dev.diena.anion.features.starship.StarshipMovement
import dev.diena.anion.features.starship.StarshipSelection
import net.minecraft.world.phys.Vec3
import org.bukkit.Bukkit
import org.bukkit.block.Block
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** blaster that grabs the starship it is shot at and drags it around with the player's crosshair. */
class AnionToolGunItem : AnionBlasterItem("Tool Gun") {

	/** a ship being carried, how far in front of the carrier's eyes it is kept, and the yaw it was grabbed at. */
	private class HeldStarship(

		val starship: Starship,
		val holdDistance: Double,
		val yawOffset: Double

	)

	private val heldStarships = ConcurrentHashMap<UUID, HeldStarship>()

	companion object {

		/** how often held ships are dragged to the crosshair, in milliseconds. */
		private const val DRAG_PERIOD_MS = 100L

	}

	init {

		Tasks.scheduleAsync(DRAG_PERIOD_MS, DRAG_PERIOD_MS, TimeUnit.MILLISECONDS, Runnable {

			if (heldStarships.isEmpty()) return@Runnable
			Tasks.runSync { dragHeldStarships() }

		})

	}

	/** right click toggles the grab: grabs the ship under the crosshair, or lets go of the one already held. */
	override fun onRightClick(player: Player) {

		if (releaseStarship(player.uniqueId)) return
		shootBullet(player)

	}

	// grabbing is right click only
	override fun onLeftClick(player: Player) {}

	override fun onRemove(player: Player) {

		if (releaseStarship(player.uniqueId)) return

	}

	override fun onHitBlock(player: Player, block: Block, hitPoint: Vector) {

		val level = (player.world as CraftWorld).handle
		val starship = Starship.starshipAt(level, block.vec3i) ?: return

		val origin = Vector(starship.origin.x.toDouble(), starship.origin.y.toDouble(), starship.origin.z.toDouble())

		// the ship keeps the facing it was grabbed with, and turns with the player from there
		val yawOffset = starship.yaw - StarshipMovement.shipYawFacing(player.eyeLocation.yaw)

		heldStarships[player.uniqueId] = HeldStarship(
			starship,
			player.eyeLocation.toVector().distance(origin),
			yawOffset
		)

		// the tool gun drives the ship by hand until it is let go
		starship.velocity.pause()

		StarshipSelection.select(player, starship)

	}

	/** lets go of the ship [playerUuid] is carrying and hands its motion back to the simulation. */
	private fun releaseStarship(playerUuid: UUID): Boolean {

		val held = heldStarships.remove(playerUuid) ?: return false
		held.starship.velocity.resume()

		return true

	}

	/** teleports every held ship onto its carrier's look ray, at the distance it was grabbed from. main thread only. */
	private fun dragHeldStarships() {

		for ((playerUuid, held) in heldStarships) {

			val player = Bukkit.getPlayer(playerUuid)
			if (player == null) {

				releaseStarship(playerUuid)
				continue

			}

			// the ship can be destroyed or unloaded while it is being carried
			if (Starship.loadedStarships[held.starship.uuid] !== held.starship) {

				releaseStarship(playerUuid)
				continue

			}

			val eyeLocation = player.eyeLocation
			val forward = eyeLocation.direction.normalize() // derived from the player's pitch and yaw
			val targetPoint = eyeLocation.toVector()+(forward*held.holdDistance)

			held.starship.teleportInWorld(
				Vec3(targetPoint.x, targetPoint.y, targetPoint.z).floorVec3i,
				preserveVelocity = true
			)

			// ship turns with the carrier, holding the yaw it had when it was grabbed
			val targetYaw = StarshipMovement.shipYawFacing(eyeLocation.yaw) + held.yawOffset
			held.starship.rotate(StarshipMovement.yawDelta(held.starship, targetYaw))

		}

	}

}
