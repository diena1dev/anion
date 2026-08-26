package dev.diena.anion.command.utils

import com.mojang.brigadier.StringReader
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import dev.astralchroma.processor.annotations.Command
import dev.astralchroma.processor.annotations.CustomType
import dev.astralchroma.processor.annotations.Name
import dev.astralchroma.processor.annotations.Permission
import dev.astralchroma.processor.annotations.Sender
import dev.astralchroma.processor.annotations.Subcommand
import dev.diena.anion.Keys
import dev.diena.anion.data.registry.AnionRegistryKey
import dev.diena.anion.data.registry.registries.AnionRegistries
import dev.diena.anion.extensions.placeAsPlayer
import dev.diena.anion.extensions.plus
import dev.diena.anion.extensions.times
import dev.diena.anion.extensions.toBlockPos
import dev.diena.anion.extensions.vec3i
import dev.diena.anion.features.machine.Machine
import io.papermc.paper.command.brigadier.MessageComponentSerializer
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import io.papermc.paper.command.brigadier.argument.CustomArgumentType
import net.kyori.adventure.key.Key
import net.kyori.adventure.key.Key.key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Color
import org.bukkit.craftbukkit.CraftWorld
import org.bukkit.entity.Player
import java.util.concurrent.CompletableFuture

@Command
@Name("machine")
@Permission("${Keys.COMMAND_PERMISSION_TREE}.machine")
object MachineCommand {

	/** how far in front of the sender a placed structure's core lands. */
	private const val PLACEMENT_DISTANCE = 2

	/** Stamps a machine type's structure into the world in front of the sender. Does not assemble it. */
	@Subcommand
	@Permission("${Keys.COMMAND_PERMISSION_TREE}.machine.place")
	fun place(

		@Sender sender: Player,
		@CustomType(MachineType::class) machineKey: Key

	) {

		val blockSet = AnionRegistries.MACHINE_TYPE_REGISTRY.getValue(AnionRegistryKey(machineKey.value()))?.invoke()?.blockSet
		if (blockSet == null) {

			sender.info("No machine type registered under \"${machineKey.value()}\".")
			return

		}

		// horizontal facing only, so looking at your feet does not bury the structure in the floor
		val anchor = sender.location.toBlockPos() + (sender.facing.vec3i * PLACEMENT_DISTANCE)

		// multiple variants can be valid at a cell — the first is placed as a representative
		val placements = blockSet.blockMap.map { (offset, variants) ->

			val target = anchor + offset
			sender.world.getBlockAt(target.x, target.y, target.z) to variants.first().representative()

		}

		val occupied = placements.count { (block, _) -> !block.isReplaceable }
		if (occupied > 0) {

			sender.info("$occupied of ${placements.size} cells are already occupied. Clear the area first.")
			return

		}

		// snapshots first: a protection plugin can deny a cell halfway through, and half a machine
		// stamped into someone else's claim is worse than none of it.
		val replaced = placements.map { (block, _) -> block.state }

		for ((block, blockData) in placements) {

			if (block.placeAsPlayer(sender, blockData)) continue

			for (state in replaced) state.update(true, false)

			sender.info("Placement denied at ${block.x}, ${block.y}, ${block.z}. Nothing was placed.")
			return

		}

		sender.info("Placed \"${blockSet.name}\" (${placements.size} blocks) at $anchor. Not assembled.")

	}

	/** Assembles whatever machine the clicked block belongs to, at any offset and rotation. */
	@Subcommand
	@Permission("${Keys.COMMAND_PERMISSION_TREE}.machine.assemble")
	fun assemble(

		@Sender sender: Player

	) {

		val target = sender.getTargetBlockExact(16) ?: run {
			sender.info("No block in range (max 16 blocks).")
			return
		}

		val level = (sender.world as CraftWorld).handle
		val clicked = target.vec3i

		val candidates = Machine.candidatesAt(level, clicked)

		if (candidates.isEmpty()) {
			sender.info("Unable to assemble a Machine at $clicked.")
			return
		}

		// a block that completes two different structures at once is an ambiguity, not a coin flip
		if (candidates.size > 1) {
			sender.info("Machine ambiguity at $clicked: ${candidates.size} structures match.")
			return
		}

		val candidate = candidates.first()
		val machine = candidate.factory()
			.assemble(level, candidate.origin, candidate.rotation)
			?: run {
				sender.info("A port block in the structure already belongs to another machine.")
				return
			}

		sender.info("Assembled ${machine::class.simpleName} at ${candidate.origin} (${candidate.rotation}). Intact: ${machine.intact}")

	}

	/** Tears down every machine occupying the targeted block. */
	@Subcommand
	@Permission("${Keys.COMMAND_PERMISSION_TREE}.disassemble")
	fun disassemble(

		@Sender sender: Player

	) {

		val machines = machinesAt(sender) ?: return

		// machines may share structure cells, so a single block can belong to more than one
		for (machine in machines) {
			val name = machine.namespacedKey.key
			machine.disassemble()
			sender.info("Disassembled $name.")
		}

	}

	/** Empties a buffer onto the floor. The way out of loading the wrong resource into the wrong one. */
	@Subcommand
	@Permission("${Keys.COMMAND_PERMISSION_TREE}.clear")
	fun clearBuffer(

		@Sender sender: Player,
		buffer: String,

	) {

		val machines = machinesAt(sender) ?: return

		for (machine in machines) {

			val store = machine.buffers[buffer]

			if (store == null) {
				val known = machine.buffers.keys.joinToString(", ").ifEmpty { "none" }
				sender.info("${machine.namespacedKey.key} has no buffer '$buffer'. it has: $known")
				continue
			}

			if (!store.spillable) {
				sender.info("'$buffer' disassemble the machine to empty this buffer.")
				continue
			}

			val held = store.used()
			machine.spill(store)

			sender.info("Cleared ${machine.namespacedKey.key} buffer '$buffer', dropped $held.")

		}

	}

	/** Dumps structure, port and buffer state for the machine(s) at the targeted block. */
	@Subcommand
	@Permission("${Keys.COMMAND_PERMISSION_TREE}.debug")
	fun debug(

		@Sender sender: Player

	) {

		val machines = machinesAt(sender) ?: return

		for (machine in machines) {
			for (line in machine.debugReport()) sender.info(line)
		}

	}

	/////////////
	///// HELPERS
	/////////////

	/** Every machine holding the block the sender is looking at, or null once the sender has been told why not. */
	private fun machinesAt(sender: Player): List<Machine>? {

		val target = sender.getTargetBlockExact(16) ?: run {
			sender.info("No block in range (max 16 blocks).")
			return null
		}

		val level = (sender.world as CraftWorld).handle
		val cell = target.vec3i

		val machines = Machine.machinesAt(level, cell)

		if (machines.isEmpty()) {
			sender.info("No machine at $cell.")
			return null
		}

		return machines

	}

	private fun Player.info(message: String) {

		this.sendMessage(
			Component.text("[Machine] ").color(TextColor.color(Color.AQUA.asARGB()))
				.append(Component.text(message).color(TextColor.color(Color.WHITE.asARGB())))
		)

	}

}

object MachineType : CustomArgumentType<Key, Key> {
	private val MACHINE_TYPE_DOES_NOT_EXIST_ERROR = DynamicCommandExceptionType { key ->
		MessageComponentSerializer.message().serialize(Component.text("\"$key\" does not exist!"))
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
