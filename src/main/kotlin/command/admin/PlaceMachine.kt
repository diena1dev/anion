package dev.diena.anion.command.admin

import com.mojang.brigadier.StringReader
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import dev.astralchroma.processor.annotations.Command
import dev.astralchroma.processor.annotations.CustomType
import dev.astralchroma.processor.annotations.Inferred
import dev.astralchroma.processor.annotations.Name
import dev.astralchroma.processor.annotations.Permission
import dev.astralchroma.processor.annotations.Sender
import dev.diena.anion.Keys
import dev.diena.anion.data.registry.AnionRegistryKey
import dev.diena.anion.data.registry.registries.AnionRegistries
import dev.diena.anion.extensions.plus
import dev.diena.anion.extensions.times
import io.papermc.paper.command.brigadier.MessageComponentSerializer
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import io.papermc.paper.command.brigadier.argument.CustomArgumentType
import net.kyori.adventure.key.Key
import net.kyori.adventure.key.Key.key
import net.kyori.adventure.text.Component.text
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import java.util.concurrent.CompletableFuture

@Command
@Name("placemachine")
@Permission("${Keys.COMMAND_PERMISSION_TREE}.placemachine")
object PlaceMachine {

	@Inferred
	fun self(

		@Sender sender: Player,
		@CustomType(MachineType::class) machineKey: Key

	) {

		val machineBlocks = AnionRegistries.MACHINE_TYPE_REGISTRY.getValue(AnionRegistryKey(machineKey.value()))?.invoke()?.blockSet ?: return

		sender.sendMessage("[debug] placed machine structure (not assembled)")

		val forward = sender.location.direction
		for ((pos, variants) in machineBlocks.blockMap) {

			// multiple variants can be valid at a cell — just place the first as a representative
			sender.world
				.setBlockData((forward*2+sender.location.toVector()+Vector(pos.x, pos.y, pos.z)), variants.first().representative())

		}

	}

}

object MachineType : CustomArgumentType<Key, Key> {
	private val MACHINE_TYPE_DOES_NOT_EXIST_ERROR = DynamicCommandExceptionType { key ->
		MessageComponentSerializer.message().serialize(text("\"$key\" does not exist!"))
	}

	override fun parse(reader: StringReader): Key {
		var argument = ""

		while (reader.canRead()) {
			if (reader.peek().isWhitespace()) break
			argument += reader.read()
		}

		val parts = argument.split(':', limit = 2)

		val namespace = if (parts.size == 2) parts.first() else ""
		val value = parts.last()

		val key = key(namespace, value)

		if (AnionRegistries.MACHINE_TYPE_REGISTRY.all[AnionRegistryKey(key.value())] != null) return key

		// if we cannot find the machine, throw this
		throw MACHINE_TYPE_DOES_NOT_EXIST_ERROR.create(key)
	}

	override fun getNativeType() = ArgumentTypes.key()

	// Lazily loaded to avoid burning a second on startup
	private val suggestions: List<String> by lazy {
		val suggestions = mutableListOf<String>()

		for (item in AnionRegistries.MACHINE_TYPE_REGISTRY.all) suggestions.add(item.key.toString())

		suggestions
	}

	override fun <S : Any> listSuggestions(
		context: CommandContext<S>,
		builder: SuggestionsBuilder
	): CompletableFuture<Suggestions> {
		val start = builder.remainingLowerCase

		suggestions
			.filter { it.startsWith(start) }
			.forEach { builder.suggest(it) }

		return builder.buildFuture()
	}
}

