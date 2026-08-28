package dev.diena.anion.command.arguments

import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import dev.diena.anion.features.space.validation.SpaceValidation
import dev.diena.anion.features.space.validation.Validation
import io.papermc.paper.command.brigadier.MessageComponentSerializer
import io.papermc.paper.command.brigadier.argument.CustomArgumentType
import net.kyori.adventure.text.Component.text
import java.util.concurrent.CompletableFuture

/** suggests and resolves the name of a registered space validation group. */
object ValidationGroup : CustomArgumentType<String, String> {

	private val GROUP_DOES_NOT_EXIST_ERROR = DynamicCommandExceptionType { name ->
		MessageComponentSerializer.message().serialize(text("\"$name\" is not a validation group!"))
	}

	override fun parse(reader: StringReader): String {

		var argument = ""

		while (reader.canRead()) {
			if (reader.peek().isWhitespace()) break
			argument += reader.read()
		}

		SpaceValidation // force group registration before we look anything up

		if (Validation.group(argument) != null) return argument

		throw GROUP_DOES_NOT_EXIST_ERROR.create(argument)

	}

	override fun getNativeType() = StringArgumentType.word()

	override fun <S : Any> listSuggestions(
		context: CommandContext<S>,
		builder: SuggestionsBuilder
	): CompletableFuture<Suggestions> {

		SpaceValidation

		val start = builder.remainingLowerCase

		Validation.groupNames()
			.filter { it.startsWith(start) }
			.forEach { builder.suggest(it) }

		return builder.buildFuture()

	}

}
