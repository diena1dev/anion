package dev.diena.anion.command.utils

import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import dev.astralchroma.processor.annotations.Command
import dev.astralchroma.processor.annotations.Inferred
import dev.astralchroma.processor.annotations.Name
import dev.astralchroma.processor.annotations.Permission
import dev.astralchroma.processor.annotations.Sender
import dev.astralchroma.processor.annotations.Suggester
import dev.diena.anion.Keys
import io.papermc.paper.command.brigadier.CommandSourceStack
import org.bukkit.entity.Player
import java.util.concurrent.CompletableFuture

// <speed>
// <speed> <player>
// <type> <speed>
// <type> <speed> <player>

@Command
@Name("speed")
@Permission("${Keys.COMMAND_PERMISSION_TREE}.speed")
object SpeedCommand {
	@Inferred
	@Permission("${Keys.COMMAND_PERMISSION_TREE}.speed.self")
	fun self(
		@Sender sender: Player,
		@Suggester(SpeedIntegerAndModeStringSuggester::class) speed: Float
	) = otherType(sender, if (sender.isFlying) "flying" else "walking", speed, sender)

	@Inferred
	@Permission("${Keys.COMMAND_PERMISSION_TREE}.speed.self")
	fun selfType(
		@Sender sender: Player,
		type: String,
		@Suggester(SpeedIntegerAndModeStringSuggester::class) speed: Float
	) = otherType(sender, type, speed, sender)

	@Inferred
	@Permission("${Keys.COMMAND_PERMISSION_TREE}.speed.other")
	fun other(
		@Sender sender: Player,
		@Suggester(SpeedIntegerAndModeStringSuggester::class) speed: Float,
		target: Player
	) = otherType(sender, if (sender.isFlying) "flying" else "walking", speed, target)

	@Inferred
	@Permission("${Keys.COMMAND_PERMISSION_TREE}.speed.other")
	fun otherType(
		@Sender sender: Player,
		type: String,
		@Suggester(SpeedIntegerAndModeStringSuggester::class) speed: Float,
		target: Player
	) {
		// if invalid, convert to valid parameters
		val speedFactor = if (speed in 0.1f..10f) {
			speed
		} else { // intended to round to 1f instead of 0.1f
			if (speed > 10f) 10f else 1f
		}

		// mode selector logic
		when (type) {
			"walking" -> changeWalkingSpeed(sender, target, speedFactor)
			"flying" -> changeFlyingSpeed(sender, target, speedFactor)
		}
	}

	object SpeedIntegerAndModeStringSuggester : SuggestionProvider<CommandSourceStack> {
		override fun getSuggestions(
			context: CommandContext<CommandSourceStack>,
			builder: SuggestionsBuilder
		): CompletableFuture<Suggestions> {
			builder.suggest(1)
			builder.suggest(3)
			builder.suggest(5)
			builder.suggest(10)
			builder.suggest("walking")
			builder.suggest("flying")

			return builder.buildFuture()
		}
	}

	private fun changeWalkingSpeed(
		sender: Player,
		target: Player,
		speedFactor: Float
	) {
		val newWalkSpeed = when (speedFactor) {
			1f -> 0.2f // i'm going to do something silly here and make a hard minimum limit for speed equal to vanilla walking speed.
			2f -> 0.25f // because 1 is going to be interpreted as 0.2f, we need to compensate bt shifting the *actual* value to something higher.
			else -> speedFactor * 0.1f
		}

		target.walkSpeed = newWalkSpeed

		sender.sendMessage("Set ${if (sender.name == target.name) "own" else "${target.name}'s"} walking speed to $speedFactor")
	}

	private fun changeFlyingSpeed(
		sender: Player,
		target: Player,
		speedFactor: Float
	) {
		val newFlyingSpeed = speedFactor * 0.1f

		target.flySpeed = newFlyingSpeed

		sender.sendMessage("Set ${if (sender.name == target.name) "own" else "${target.name}'s"} flying speed to $speedFactor")
	}
}
