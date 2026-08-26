package dev.diena.anion.features.machine.machine_types.scripting.mainframe

import dev.diena.anion.features.custom.blocks.AnionBlocks
import dev.diena.anion.features.machine.BlockSet
import dev.diena.anion.features.machine.Machine
import dev.diena.anion.features.machine.component.MachineSign
import dev.diena.anion.features.machine.machine_types.PortedMachine
import dev.diena.anion.features.scripting.DcGroups
import dev.diena.anion.features.scripting.DcProgram
import dev.diena.anion.features.scripting.DcRuntime
import dev.diena.anion.features.scripting.DcStore
import dev.diena.anion.features.transport.DataNetwork
import net.minecraft.core.Vec3i
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import org.bukkit.block.BlockType
import org.bukkit.entity.Player
import java.util.UUID
import kotlin.collections.iterator

/**
 * Two courses of casing with a dataport up the back and a row of signs across the front. The signs are
 * the console; the dataports are where the network is wired in.
 */
val MAINFRAME_STRUCTURE = BlockSet.new("mainframe")

	.core('C', AnionBlocks.COPPER_MACHINE_DATAPORT)

	.assign('c', AnionBlocks.COPPER_MACHINE_CASING)
	.assign('S', BlockType.PALE_OAK_WALL_SIGN)
	.assign('d', BlockType.POLISHED_DEEPSLATE_STAIRS)
	.assign('i', BlockType.IRON_BLOCK)

	.slice(
		" i",
		" C",
		" i",
	)
	.slice(
		"Sd",
		"Sc",
		"Sd",
	)

	.build()

/**
 * The machine that holds the .dcprgm files and drives everything wired to it.
 *
 * A mainframe reaches machines over data lines, never by proximity and never by carrier — it works on a
 * ship and on the ground, and linking a reactor to its control panel is the same mechanism as flying.
 *
 * Two mainframes wired to the same machines keep disjoint sets: their own names, their own files, their
 * own programs. Nothing here is shared between them.
 */
class MainframeMachine : PortedMachine("Mainframe", MAINFRAME_STRUCTURE) {

	companion object {

		/** The mainframe [machine] is wired to, or null when nothing is. */
		// first one found: a machine wired to two mainframes is legal, and either may command it
		fun of(machine: Machine): MainframeMachine? =
			DataNetwork.reachableFrom(machine).filterIsInstance<MainframeMachine>().firstOrNull()

		/** what a machine name is allowed to look like, so a rename cannot produce something unparseable */
		private val NAME_PATTERN = Regex("[a-z0-9_]+")

	}

	val store: DcStore = DcStore.new(this)
	val runtime: DcRuntime = DcRuntime.new(this)
	val console: MainframeConsole = MainframeConsole.new(this)

	/** every name this mainframe has handed out, and what it was given to. names are never reused. */
	// kept for machines that have been unplugged, so their file survives being rewired
	private val attached: MutableMap<String, UUID> = mutableMapOf()

	/** names the last network walk actually reached */
	private var online: Set<String> = emptySet()

	val machineNames: Set<String> get() = attached.keys
	val groups: DcGroups get() = store.groups

	/** names the last walk reached — connected right now, rather than merely known */
	val onlineNames: Set<String> get() = online

	fun isOnline(machineName: String): Boolean = machineName in online

	/** The live machine behind [machineName], or null while it is unplugged, unloaded or broken. */
	fun machineNamed(machineName: String): Machine? {

		val uuid = attached[machineName] ?: return null

		return activeMachines[uuid]?.takeIf { it.intact }
	}

	/** What this mainframe calls [machine], or null if it has never been wired to it. */
	fun nameOf(machine: Machine): String? = attached.entries.firstOrNull { it.value == machine.uuid }?.key

	fun programOf(machineName: String): DcProgram? = store.programOf(machineName)

	/** Files changed, so the mainframe owes the database a write. */
	internal fun markProgramsDirty() = markDirty()

	// dispatch happens on the emitting machine's tick, so a mainframe with nothing to walk does nothing
	override fun tick() {
	}

	override fun slowTick() {

		rewalk()

	}

	override fun onAssemble() {

		super.onAssemble()

		MachineSign.seal(this)
		store.recompile()
		console.render()

	}

	override fun onSignClick(offset: Vec3i, player: Player, front: Boolean) {

		console.click(offset, player)

	}

	/**
	 * Gives [machineName] a new name, rewriting every file that mentions the old one.
	 *
	 * Returns why it was refused, or null when it went through. Files are rewritten textually rather than
	 * left to break: a rename that silently stopped a group compiling would be worse than no rename.
	 */
	fun rename(machineName: String, newName: String): String? {

		if (machineName !in attached) return "'$machineName' is not a machine this mainframe knows"
		if (newName == machineName) return null

		if (!NAME_PATTERN.matches(newName)) return "names may only use lowercase letters, digits and underscores"
		if (newName in attached) return "'$newName' is already taken"
		if (newName in groups.names) return "'$newName' is a group"

		val uuid = attached.remove(machineName) ?: return "'$machineName' is not a machine this mainframe knows"
		attached[newName] = uuid

		store.rename(machineName, newName)
		runtime.forget(machineName) // latches are keyed on the name that just stopped existing

		markDirty()

		console.render()

		return null

	}

	internal fun openRenameDialog(player: Player, machineName: String) =
		MainframeDialogs.openRename(this, machineName, player)

	internal fun openEditorDialog(player: Player, machineName: String) =
		MainframeDialogs.openEditor(this, machineName, player)

	internal fun openGroupsDialog(player: Player) =
		MainframeDialogs.openGroupsEditor(this, player)

	override fun debugLines(): List<String> {

		val lines = mutableListOf<String>()

		lines += "attached=${attached.size} online=${online.size}"

		for ((machineName, uuid) in attached) {

			val machine = activeMachines[uuid]
			val state = if (machineName in online) "ONLINE" else "offline"

			lines += "  $machineName $state ${machine?.namespacedKey?.key ?: "unloaded"}"

		}

		lines += DataNetwork.describe(this)
		lines += store.debugLines()

		return lines

	}

	/** Names, and the text of every file, keyed by the machine it belongs to. */
	override fun saveState(tag: CompoundTag) {

		super.saveState(tag)

		val stored = ListTag()

		for ((machineName, uuid) in attached) {

			val entry = CompoundTag()
			entry.putString("name", machineName)
			entry.putString("uuid", uuid.toString())
			entry.putString("source", store.sourceOf(machineName))

			stored.add(entry)

		}

		tag.put("attached", stored)
		tag.putString("groups", store.groupsSource)

	}

	// runs before onAssemble(), which is where the restored text gets compiled
	override fun loadState(tag: CompoundTag) {

		super.loadState(tag)

		val sources = mutableMapOf<String, String>()

		attached.clear()

		for (entry in tag.getListOrEmpty("attached").filterIsInstance<CompoundTag>()) {

			val machineName = entry.getStringOr("name", "")
			if (machineName.isEmpty()) continue

			val uuid = runCatching { UUID.fromString(entry.getStringOr("uuid", "")) }.getOrNull() ?: continue
			attached[machineName] = uuid

			val source = entry.getStringOr("source", "")
			if (source.isNotEmpty()) sources[machineName] = source

		}

		store.restore(tag.getStringOr("groups", ""), sources)

	}

	/** Re-walks the data network, naming anything newly wired in. */
	// what came and went decides whether the files need rebuilding: a program compiles against the
	// machines that are actually there
	private fun rewalk() {

		val reached = DataNetwork.reachableFrom(this)

		val names = mutableSetOf<String>()
		for (machine in reached) names += nameOf(machine) ?: assignName(machine)

		if (names == online) return

		online = names
		store.recompile()

		console.render() // the console counts what is connected, so it changes when this does

	}

	/** Hands [machine] the next free name for its type. */
	private fun assignName(machine: Machine): String {

		val base = machine.namespacedKey.key

		var index = 1
		while ("${base}_$index" in attached) index++

		val machineName = "${base}_$index"

		attached[machineName] = machine.uuid
		markDirty()

		return machineName

	}

}
