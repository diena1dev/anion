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
import org.bukkit.block.Block
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.entity.Player
import org.bukkit.util.Vector
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

	private var heldStarship: HeldStarship? = null
	private var playerHolding: Player? = null
	private var shotMode = ShotMode.GRAB

	companion object {

		/** how often held ships are dragged to the crosshair, in milliseconds. */
		private const val DRAG_PERIOD_MS = 100L

	}

	init {

		Tasks.scheduleAsync(DRAG_PERIOD_MS, DRAG_PERIOD_MS, TimeUnit.MILLISECONDS, Runnable {

			if (heldStarship == null) return@Runnable
			Tasks.runSync { dragHeldStarship() }

		})

	}

	/** right click toggles the grab: grabs the ship under the crosshair, or lets go of the one already held. */
	override fun onRightClick(player: Player) {

		if (releaseStarship()) return

		shotMode = ShotMode.GRAB
		shootBullet(player)

	}

	/** left click freezes the ship being held, or the ship the shot lands on. */
	override fun onLeftClick(player: Player) {

		val held = heldStarship?.starship

		// a frozen ship must not still be dragged to the crosshair
		if (held != null) {

			releaseStarship()
			held.velocity.toggleFreeze()
			return

		}

		shotMode = ShotMode.FREEZE
		shootBullet(player)

	}

	override fun onRemove(player: Player) {

		if (releaseStarship()) return

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

		heldStarship = HeldStarship(
			starship,
			player.eyeLocation.toVector().distance(origin),
			yawOffset
		)
		playerHolding = player

		// the tool gun drives the ship by hand until it is let go
		starship.velocity.pause()

	}

	/** Releases the ship [playerUuid] is carrying and hands its motion back to the simulation. */
	private fun releaseStarship(): Boolean {

		val held = heldStarship?.starship ?: return false
		held.velocity.resume()

		heldStarship = null
		playerHolding = null

		return true

	}

	/** Teleports every held ship onto its carrier's look ray, at the distance it was grabbed from. */
	private fun dragHeldStarship() {

		if (playerHolding == null) { releaseStarship(); return }
		if (heldStarship == null) return

		// the ship can be destroyed or unloaded while it is being carried
		if (Starship.loadedStarships[heldStarship!!.starship.uuid] != heldStarship!!.starship) {
			releaseStarship()
		}

		val eyeLocation = playerHolding!!.eyeLocation
		val forward = eyeLocation.direction.normalize() // derived from the player's pitch and yaw
		val targetPoint = eyeLocation.toVector()+(forward*heldStarship!!.holdDistance)

		if (targetPoint != heldStarship!!.starship.origin) return // why would we move it when it's not moving lol

		heldStarship!!.starship.teleportInWorld(
			Vec3(targetPoint.x, targetPoint.y, targetPoint.z).floorVec3i,
			preserveVelocity = true
		)

		// ship turns with the carrier, holding the yaw it had when it was grabbed
		val targetYaw = StarshipMovement.shipYawFacing(eyeLocation.yaw) + heldStarship!!.yawOffset
		heldStarship!!.starship.rotate(StarshipMovement.yawDelta(heldStarship!!.starship, targetYaw))

	}

}
