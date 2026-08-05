package dev.diena.anion.features.custom.items

import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.util.Vector

class AnionBlasterPistolItem : AnionBlasterItem("Blaster Pistol") {

	/** yeets whatever the shot lands on five blocks upward. */
	override fun onHitEntity(player: Player, entity: Entity, hitPoint: Vector) {

		entity.velocity = Vector(0, 1, 0)

	}

}
