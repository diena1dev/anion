package dev.diena.anion.features.starship

import dev.diena.anion.Anion
import dev.diena.anion.extensions.vec3i
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

/** tracks which starship a player is currently acting on. stored on the player, so it survives relogs. */
object StarshipSelection {

	private val SELECTED_KEY = NamespacedKey(Anion.NAMESPACE, "selected_starship")

	/** the ship [player] has selected, or null if none is selected or it is no longer loaded. */
	fun selected(player: Player): Starship? {

		val storedUuid = player.persistentDataContainer.get(SELECTED_KEY, PersistentDataType.STRING) ?: return null
		return Starship.loadedStarships[runCatching { UUID.fromString(storedUuid) }.getOrNull() ?: return null]

	}

	/** selects [starship] for [player]. */
	fun select(player: Player, starship: Starship) {

		player.persistentDataContainer.set(SELECTED_KEY, PersistentDataType.STRING, starship.uuid.toString())

	}

	/** selects the loaded ship whose origin is nearest to [player]. returns it, or null if no ship is loaded. */
	fun selectNearest(player: Player): Starship? {

		val playerPosition = player.location.block.vec3i
		val nearest = Starship.loadedStarships.values.minByOrNull { it.origin.distSqr(playerPosition) } ?: return null

		select(player, nearest)
		return nearest

	}

	/** clears [player]'s selection. */
	fun clear(player: Player) {

		player.persistentDataContainer.remove(SELECTED_KEY)

	}

}
