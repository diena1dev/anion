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

/** Tool that grabs the starship it is shot at and drags it around with the player's crosshair. */
class AnionToolGunItem : AnionBlasterItem("Tool Gun") {

	/** a ship being carried, how far in front of the carrier's eyes it is kept, and the yaw it was grabbed at. */
	private class HeldStarship(

		val starship: Starship,
		val holdDistance: Double,
		val yawOffset: Double

	)

	/** what the next shot does to the ship it lands on. */
	private enum class ShotMode { GRAB, FREEZE }

	/** one carried ship per player. */
	private val heldStarships = ConcurrentHashMap<UUID, HeldStarship>()

	// shootBullet resolves onHitBlock inline on the main thread, so the mode is only ever read back by
	// the shot that set it.
	private var shotMode = ShotMode.GRAB

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

	/** right click toggles the grab: grabs the ship under the crosshair, or lets go of the one already held. another thing it does is unfreezes a starship. */
	override fun onRightClick(player: Player) {

		if (releaseStarship(player.uniqueId)) return

		shotMode = ShotMode.GRAB
		shootBullet(player)
		heldStarships[player.uniqueId]?.starship?.velocity?.unfreeze()

	}

	/** left click freezes the ship [player] is holding, or the ship their shot lands on. */
	override fun onLeftClick(player: Player) {

		val held = heldStarships[player.uniqueId]?.starship

		// a frozen ship must not still be dragged to the crosshair
		if (held != null) {

			releaseStarship(player.uniqueId)
			held.velocity.toggleFreeze()
			return

		}

		shotMode = ShotMode.FREEZE
		shootBullet(player)

	}

	override fun onRemove(player: Player) {

		if (releaseStarship(player.uniqueId)) return

	}

	override fun onHitBlock(player: Player, block: Block, hitPoint: Vector) {

		val level = (player.world as CraftWorld).handle
		val starship = Starship.starshipAt(level, block.vec3i) ?: return

		StarshipSelection.select(player, starship)

		when (shotMode) {

			ShotMode.FREEZE -> starship.velocity.toggleFreeze()
			ShotMode.GRAB -> grabStarship(player, starship)

		}

	}

	/** puts [starship] on [player]'s crosshair and takes over its motion until it is released. */
	private fun grabStarship(player: Player, starship: Starship) {

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
			val targetBlock = Vec3(targetPoint.x, targetPoint.y, targetPoint.z).floorVec3i

			// why would we move it when it's not moving lol
			if (targetBlock != held.starship.origin) {
				held.starship.teleportInWorld(targetBlock, preserveVelocity = true)
			}

			// ship turns with the carrier, holding the yaw it had when it was grabbed
			val targetYaw = StarshipMovement.shipYawFacing(eyeLocation.yaw) + held.yawOffset
			held.starship.rotate(StarshipMovement.yawDelta(held.starship, targetYaw))

		}

	}

}
