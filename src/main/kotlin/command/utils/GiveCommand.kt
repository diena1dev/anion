package dev.diena.anion.command.utils

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
import io.papermc.paper.command.brigadier.MessageComponentSerializer
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import io.papermc.paper.command.brigadier.argument.CustomArgumentType
import net.kyori.adventure.key.Key
import net.kyori.adventure.key.Key.key
import net.kyori.adventure.text.Component.text
import org.bukkit.Registry
import org.bukkit.entity.Player
import java.util.concurrent.CompletableFuture

@Command
@Name("give")
@Permission("${Keys.COMMAND_PERMISSION_TREE}.give")
object GiveCommand {

	@Inferred
	@Permission("${Keys.COMMAND_PERMISSION_TREE}.give.self")
	fun self(

		@Sender sender: Player, // require player
		@CustomType(Item::class) itemKey: Key,
		amount: Int,

	) = give(sender, itemKey, amount)

	@Inferred
	@Permission("${Keys.COMMAND_PERMISSION_TREE}.give.self")
	fun self(

		@Sender sender: Player, // require player
		@CustomType(Item::class) itemKey: Key,
		target: Player,
		amount: Int,

		) = give(sender, itemKey, amount, target)

	// helper function
	fun give(sender: Player, key: Key, amount: Int = 1, target: Player = sender) {
		fun fail() = sender.sendMessage(text("ohnaurrrr we can't fwind the item sworry >w<"))

		val stack = when (key.namespace()) {
			"minecraft" -> Registry.ITEM[key]!!.createItemStack(amount)
			"anion" -> AnionRegistries.ITEM_REGISTRY.all[AnionRegistryKey(key.value())]!!.asItemStack(1)
			else -> {
				fail()
				return
			}
		}

		sender.sendMessage(
			text("Gave $amount ")
				.append(stack.displayName())
				.append(text(" to "))
				.append(target.displayName()))
		target.give(stack)
	}

}

// ~~stolen~~ borrowed from solarium's give command suggester
object Item : CustomArgumentType<Key, Key> {
	private val ITEM_DOES_NOT_EXIST_ERROR = DynamicCommandExceptionType { key ->
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

		when (namespace) {
			"minecraft" -> if (Registry.ITEM[key] != null) return key
			"anion" -> if (AnionRegistries.ITEM_REGISTRY.all[AnionRegistryKey(key.value())] != null) return key
			"" -> {
				val minecraftKey = key("minecraft", value)
				val anionKey = key("anion", value)

				if (Registry.ITEM[minecraftKey] != null) return minecraftKey
				if (AnionRegistries.ITEM_REGISTRY.all[AnionRegistryKey(key.value())] != null) return anionKey
			}
		}

		throw ITEM_DOES_NOT_EXIST_ERROR.create(key)
	}

	override fun getNativeType() = ArgumentTypes.key()

	// Lazily loaded to avoid burning a second on startup
	private val suggestions: List<String> by lazy {
		val suggestions = mutableListOf<String>()

		for (item in Registry.ITEM) suggestions.add(item.key.toString())
		for (item in AnionRegistries.ITEM_REGISTRY.all) suggestions.add(item.key.toString())

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
