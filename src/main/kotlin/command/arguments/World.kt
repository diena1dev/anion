package dev.diena.anion.command.arguments

import com.mojang.brigadier.StringReader
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import io.papermc.paper.command.brigadier.MessageComponentSerializer
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import io.papermc.paper.command.brigadier.argument.CustomArgumentType
import net.kyori.adventure.key.Key
import net.kyori.adventure.key.Key.key
import net.kyori.adventure.text.Component.text
import org.bukkit.Bukkit
import java.util.concurrent.CompletableFuture

/** suggests and resolves every world currently loaded on the server. */
object World : CustomArgumentType<Key, Key> {
	private val WORLD_DOES_NOT_EXIST_ERROR = DynamicCommandExceptionType { key ->
		MessageComponentSerializer.message().serialize(text("\"$key\" does not exist!"))
	}

	override fun parse(reader: StringReader): Key {
		var argument = ""

		while (reader.canRead()) {
			if (reader.peek().isWhitespace()) break
			argument += reader.read()
		}

		val parts = argument.split(':', limit = 2)

		val namespace = if (parts.size == 2) parts.first() else "minecraft"
		val value = parts.last()

		val key = key(namespace, value)

		if (Bukkit.getWorld(key) != null) return key

		// bare folder names are what people type, so match those too
		val namedWorld = Bukkit.getWorlds().firstOrNull { it.name == value }
		if (namedWorld != null) return namedWorld.key

		throw WORLD_DOES_NOT_EXIST_ERROR.create(key)
	}

	override fun getNativeType() = ArgumentTypes.key()

	// rebuilt per call, unlike the registry suggesters — worlds load and unload at runtime
	override fun <S : Any> listSuggestions(
		context: CommandContext<S>,
		builder: SuggestionsBuilder
	): CompletableFuture<Suggestions> {
		val start = builder.remainingLowerCase

		Bukkit.getWorlds()
			.map { it.key.toString() }
			.filter { it.startsWith(start) }
			.forEach { builder.suggest(it) }

		return builder.buildFuture()
	}
}
