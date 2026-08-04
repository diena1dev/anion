package dev.diena.anion.features.custom.items

import dev.diena.anion.extensions.div
import dev.diena.anion.extensions.minus
import dev.diena.anion.extensions.plus
import dev.diena.anion.extensions.set
import dev.diena.anion.extensions.times
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.ChargedProjectiles
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.BlockType
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.ItemType
import org.bukkit.util.Vector

open class AnionBlasterItem(
	displayName: String,
	itemRepresentation: ItemType = ItemType.CROSSBOW,
	styledDisplayName: Component = Component.text(displayName)
) : AnionItem(displayName, itemRepresentation, 1) {

	val RANGE = 1..100    // how many blocks to move across
	val RESOLUTION = 1..5 // how many places within a block to move

	init {
		@Suppress("UnstableApiUsage")
		internalItemStack[DataComponentTypes.CHARGED_PROJECTILES] = ChargedProjectiles.chargedProjectiles()
			.add(ItemStack.of(Material.ARROW))
			.build()
	}

	override fun onPlayerInteract(event: PlayerInteractEvent) {
		event.isCancelled = true
		shootBullet(event.player)
	}

	override fun onEntityShootBow(event: EntityShootBowEvent) {
		event.isCancelled = true
	}

	// TODO: make into common functions that all the blaster classes can call
	private fun shootBullet(player: Player) {

		val world       = player.world               // world snapshot
		val eyeLocation = player.eyeLocation         // eye location
		val forward     = eyeLocation.direction.normalize()  // derived normal (equal to 1) direction

		val worldUp    = Vector(0.0, 1.0, 0.0)                       // world-up
		val right      = worldUp.clone().crossProduct(forward).normalize() // strafe-right  (worldUp * forward)
		val cameraDown = right.clone().crossProduct(forward).normalize()   // camera-down   (right * forward)

		// VISUAL ORIGIN, adjust values to shift origin point
		val visualOrigin = eyeLocation.toVector()+(right.clone()*-0.4)+(cameraDown.clone()*0.2)

		// visual origin debug
		world.spawnParticle(Particle.CRIT, visualOrigin.toLocation(world), 0)
		world.playSound(eyeLocation, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1f, 10f)

		rangeLoop@ for (distance in RANGE) {

			// rangeVector is what is iterated over and increased, this is what the visuals spawn on
			val rangeVector = eyeLocation.toVector()+(forward*distance)

			// RESOLUTION is to check for collision!
			for (resolutionStep in RESOLUTION) {

				val resolutionMax = RESOLUTION.last.toDouble()

				val scaledDir = forward/resolutionMax                     // scale down our forward normal by the set resolution value
				val resolutionCheck = rangeVector+(scaledDir*resolutionStep) // this is what we check for entities in

				if (world.getBlockAt(resolutionCheck.toLocation(world)).type.asBlockType() != BlockType.AIR) break@rangeLoop

				// FIXME: this is wrong, but will work (ish) for a res of 5.
				val nearbyEntities = world.getNearbyEntities(
					resolutionCheck.toLocation(world), resolutionMax*0.01, resolutionMax*0.01, resolutionMax*0.01
				)
				if (!nearbyEntities.isEmpty()) {

					val newLoc = nearbyEntities.first().location.clone()
					newLoc.y += 5

					nearbyEntities.first().teleport(newLoc)

					break@rangeLoop
				}

			}

			// spawn particle for every nearby player
			player.world.spawnParticle(Particle.END_ROD, rangeVector.toLocation(eyeLocation.world), 0)

			// spawn particle at full distance for player that shot
			player.spawnParticle(
				Particle.END_ROD, rangeVector.x, rangeVector.y, rangeVector.z, 0,
				0.0, 0.0, 0.0, 0.0, null, true)

		}
	}

}