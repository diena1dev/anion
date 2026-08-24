package dev.diena.anion.features.machine.machine_types.scripting

import dev.diena.anion.features.custom.blocks.AnionBlocks
import dev.diena.anion.features.machine.BlockSet
import dev.diena.anion.features.machine.Machine
import dev.diena.anion.features.machine.machine_types.PortedMachine
import dev.diena.anion.features.scripting.DcProgrammable
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Input
import org.bukkit.Location
import org.bukkit.attribute.Attribute
import org.bukkit.block.BlockType
import org.bukkit.entity.Player
import java.util.UUID

/** A seat with a dataport under it. Everything a pilot does with the keyboard leaves through that port. */
val CONTROL_SEAT_STRUCTURE = BlockSet.new("control_seat")

	.core('X', BlockType.POLISHED_DEEPSLATE_STAIRS)

	.assign('D', AnionBlocks.COPPER_MACHINE_DATAPORT)

	.slice("D")
	.slice("X")

	.build()

/** Where a player's keys become datachannel inputs. */
class ControlSeatMachine : PortedMachine("Control Seat", CONTROL_SEAT_STRUCTURE), DcProgrammable {

	companion object {

		/** the keys a seat reports, before the ctrl modifier doubles them */
		private val BASE_INPUTS = listOf("lc", "rc", "w", "a", "s", "d", "sp", "sh")

		val SEAT_INPUTS: List<String> = BASE_INPUTS + BASE_INPUTS.map { "alt$it" }

		/** ticks a click keeps reporting after the packet that caused it. */
		const val CLICK_DECAY = 4

		/** blocks a pilot may drift from the seat before being put back */
		private const val HOLD_SLACK = 0.01

		/** vanilla walk and fly speeds, restored to anyone found frozen with no seat to explain it */
		const val DEFAULT_WALK_SPEED = 0.2f
		const val DEFAULT_FLY_SPEED = 0.1f

		/** The seat [player] is sitting in, or null. */
		fun seatOf(player: Player): ControlSeatMachine? =
			Machine.activeMachines.values
				.filterIsInstance<ControlSeatMachine>()
				.firstOrNull { it.pilot == player.uniqueId }

		/** Gives movement back to a player who is frozen with no seat holding them. */
		fun repairFrozen(player: Player) {

			if (player.walkSpeed != 0.0f || player.flySpeed != 0.0f) return
			if (seatOf(player) != null) return

			player.walkSpeed = DEFAULT_WALK_SPEED
			player.flySpeed = DEFAULT_FLY_SPEED

			player.getAttribute(Attribute.JUMP_STRENGTH)?.let { it.baseValue = it.defaultValue }

		}

	}

	override val dataInputs: List<String> = SEAT_INPUTS

	var pilot: UUID? = null; private set

	private var mainframe: MainframeMachine? = null

	private var leftClickUntil = 0
	private var rightClickUntil = 0

	/** what the pilot could do before they sat down, put back when they stand up */
	private var savedWalkSpeed = DEFAULT_WALK_SPEED
	private var savedFlySpeed = DEFAULT_FLY_SPEED
	private var savedJumpStrength: Double? = null

	override fun tick() {

		val player = pilot?.let { Bukkit.getPlayer(it) }
		if (player == null || player.world != level.world) {

			vacate()
			return

		}

		holdInPlace(player)

		val mainframe = this.mainframe ?: return
		val seatName = mainframe.nameOf(this) ?: return

		for ((inputName, down) in inputStates(player.currentInput)) {
			mainframe.runtime.input(seatName, inputName, down)
		}

	}

	// the wiring can change under a seated pilot, so the link is re-solved rather than cached for good
	override fun slowTick() {

		if (pilot == null) return

		if (mainframeGone()) mainframe = MainframeMachine.of(this)

	}

	/** Whether the cached mainframe is broken, torn down, or was never there. */
	// a disassembled machine keeps intact=true, so being in the active list is the half that matters
	private fun mainframeGone(): Boolean {

		val cached = mainframe ?: return true

		return !cached.intact || Machine.activeMachines[cached.uuid] !== cached

	}

	override fun onDisassemble() {

		vacate()

		super.onDisassemble()

	}

	override fun debugLines(): List<String> = listOf(
		"pilot=${pilot?.let { Bukkit.getOfflinePlayer(it).name } ?: "none"}",
		"mainframe=${mainframe?.let { frame -> frame.nameOf(this) ?: "wired, unnamed" } ?: "not wired"}",
	)

	/** Sits [player] down, or stands them up if they are already here. */
	fun toggleSeat(player: Player) {

		if (pilot == player.uniqueId) {

			vacate()
			player.sendActionBar(Component.text("Exited control seat."))

			return

		}

		if (pilot != null) {

			player.sendActionBar(Component.text("Somebody is already in this seat!").color(NamedTextColor.RED))
			return

		}

		val mainframe = MainframeMachine.of(this)
		if (mainframe == null) {

			player.sendActionBar(Component.text("This seat is not connected to a mainframe.").color(NamedTextColor.RED))
			return

		}

		this.mainframe = mainframe
		pilot = player.uniqueId

		player.teleport(seatAnchor())
		lockMovement(player)

		player.sendActionBar(Component.text("Activated control seat."))

	}

	/**
	 * Pins the pilot where they are: no walking, no flying, no jumping.
	 *
	 * The keys still report — [Input] reads what is pressed, not what it moved — so a pilot with a
	 * throttle bound to `w` still drives the ship while standing perfectly still.
	 */
	private fun lockMovement(player: Player) {

		savedWalkSpeed = player.walkSpeed
		savedFlySpeed = player.flySpeed

		player.walkSpeed = 0.0f
		player.flySpeed = 0.0f

		player.getAttribute(Attribute.JUMP_STRENGTH)?.let { jump ->

			savedJumpStrength = jump.baseValue
			jump.baseValue = 0.0

		}

	}

	/** Gives [player] their movement back. */
	private fun unlockMovement(player: Player) {

		player.walkSpeed = savedWalkSpeed
		player.flySpeed = savedFlySpeed

		savedJumpStrength?.let { strength ->

			player.getAttribute(Attribute.JUMP_STRENGTH)?.baseValue = strength
			savedJumpStrength = null

		}

	}

	/** Reports a click this tick. Left clicks re-arm on every swing; right clicks are one pulse. */
	fun registerClick(right: Boolean) {

		val until = Bukkit.getCurrentTick() + CLICK_DECAY

		if (right) rightClickUntil = until else leftClickUntil = until

	}

	/** Stands the pilot up and releases everything they were driving. */
	fun vacate() {

		val seated = pilot ?: return

		val mainframe = this.mainframe
		val seatName = mainframe?.nameOf(this)

		if (mainframe != null && seatName != null) mainframe.runtime.release(seatName)

		// the player is gone on a quit, and there is nothing to give back to a player who is not here
		Bukkit.getPlayer(seated)?.let { unlockMovement(it) }

		pilot = null

	}

	/** What every seat input is doing this tick. Ctrl decides which half of the pairs is live. */
	private fun inputStates(input: Input): Map<String, Boolean> {

		val tick = Bukkit.getCurrentTick()
		val modified = input.isSprint

		val held = mapOf(
			"lc" to (leftClickUntil > tick),
			"rc" to (rightClickUntil > tick),
			"w" to input.isForward,
			"a" to input.isLeft,
			"s" to input.isBackward,
			"d" to input.isRight,
			"sp" to input.isJump,
			"sh" to input.isSneak,
		)

		val states = HashMap<String, Boolean>(SEAT_INPUTS.size)

		for ((inputName, down) in held) {

			states[inputName] = down && !modified
			states["alt$inputName"] = down && modified

		}

		return states

	}

	/** Puts a drifting pilot back on the seat, leaving them their own view direction. */
	private fun holdInPlace(player: Player) {

		val anchor = seatAnchor()
		val location = player.location

		if (location.distanceSquared(anchor) <= HOLD_SLACK) return

		anchor.yaw = location.yaw
		anchor.pitch = location.pitch

		player.teleport(anchor)

	}

	private fun seatAnchor(): Location = Location(level.world, origin.x + 0.5, origin.y + 0.1, origin.z + 0.5)

}
