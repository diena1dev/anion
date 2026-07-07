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

		val w   = player.world               // world snapshot
		val loc = player.eyeLocation         // eye location
		val np  = loc.direction.normalize()  // derived normal (equal to 1) direction

		val wu  = Vector(0.0, 1.0, 0.0)           // world-up
		val r   = wu.clone().crossProduct(np).normalize()  // strafe-right  (worldUp * forward)
		val d   = r.clone().crossProduct(np).normalize()   // camera-down   (right * forward)

		// VISUAL ORIGIN, adjust values to shift origin point
		val emo = loc.toVector()+(r.clone()*-0.4)+(d.clone()*0.2)

		// visual origin debug
		w.spawnParticle(Particle.CRIT, emo.toLocation(w), 0)

		rangeLoop@ for (i in RANGE) {

			// rangeVector is what is iterated over and increased, this is what the visuals spawn on
			val rangeVector = loc.toVector()+(np*i)

			// RESOLUTION is to check for collision!
			for (r in RESOLUTION) {

				val resMax = RESOLUTION.last.toDouble()

				val scaledDir = np/resMax          // scale down our normal (np) by the set resolution value
				val rc = rangeVector+(scaledDir*r) // this (resolutionCheck) is what we check for entities in

				if (w.getBlockAt(rc.toLocation(w)).type.asBlockType() != BlockType.AIR) break@rangeLoop

				// FIXME: this is wrong, but will work (ish) for a res of 5.
				val nearbyEntities = w.getNearbyEntities(
					rc.toLocation(w), resMax*0.01, resMax*0.01, resMax*0.01
				)
				if (!nearbyEntities.isEmpty()) {

					val newLoc = nearbyEntities.first().location.clone()
					newLoc.y += 5

					nearbyEntities.first().teleport(newLoc)

					break@rangeLoop
				}

			}

			// spawn particle for every nearby player
			player.world.spawnParticle(Particle.END_ROD, rangeVector.toLocation(loc.world), 0)

			// spawn particle at full distance for player that shot
			player.spawnParticle(
				Particle.END_ROD, rangeVector.x, rangeVector.y, rangeVector.z, 0,
				0.0, 0.0, 0.0, 0.0, null, true)

		}
	}

}