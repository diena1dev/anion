package dev.diena.anion.features.machine.machine_types.scripting

import dev.diena.anion.features.custom.blocks.AnionBlocks
import dev.diena.anion.features.machine.BlockSet
import dev.diena.anion.features.machine.Machine
import dev.diena.anion.features.machine.machine_types.PortedMachine
import dev.diena.anion.features.machine.machine_types.scripting.mainframe.MainframeMachine
import dev.diena.anion.features.scripting.DcProgrammable
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minecraft.world.entity.PositionMoveRotation
import net.minecraft.world.entity.Relative
import net.minecraft.world.phys.Vec3
import org.bukkit.Bukkit
import org.bukkit.Input
import org.bukkit.Location
import org.bukkit.attribute.Attribute
import org.bukkit.block.BlockType
import org.bukkit.craftbukkit.entity.CraftPlayer
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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

		/**
		 * blocks squared a pilot may end up from the seat before being put back.
		 *
		 * Tight, because the move listener refuses walking outright — anything that gets past it moved the
		 * player without asking, and should be corrected the same tick rather than left to drift.
		 */
		private const val HOLD_SLACK = 0.1

		/** vanilla walk and fly speeds, restored to anyone found frozen with no seat to explain it */
		const val DEFAULT_WALK_SPEED = 0.2f
		const val DEFAULT_FLY_SPEED = 0.1f

		/** the seat each pilot is in. a map rather than a scan — PlayerMoveEvent fires on every step. */
		private val seats: MutableMap<UUID, ControlSeatMachine> = ConcurrentHashMap()

		/** The seat [player] is sitting in, or null. */
		// a seat that left the active list without vacating would otherwise pin its pilot forever
		fun seatOf(player: Player): ControlSeatMachine? =
			seats[player.uniqueId]?.takeIf { Machine.activeMachines[it.uuid] === it }

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

		// a pilot who cannot move cannot sprint, and toggle-sprint latching on would pin the ctrl
		// modifier true forever — every plain input would report false for the rest of the flight
		if (player.isSprinting) player.isSprinting = false

		val mainframe = this.mainframe ?: return
		val seatName = mainframe.nameOf(this) ?: return

		// the pilot is owed an explanation for controls that just went dead
		if (mainframe.budget.tripped) {

			player.sendActionBar(
				Component.text("MAINFRAME OVERRUN | rebooting in ${mainframe.budget.rebootRemaining / 20 + 1}s")
					.color(NamedTextColor.RED)
			)
			return

		}

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

	override fun debugLines(): List<String> {

		val seated = pilot?.let { Bukkit.getPlayer(it) }

		val lines = mutableListOf(
			"pilot=${pilot?.let { Bukkit.getOfflinePlayer(it).name } ?: "none"}",
			"mainframe=${mainframe?.let { frame -> frame.nameOf(this) ?: "wired, unnamed" } ?: "not wired"}",
		)

		// which half of every input pair is live. a modifier stuck on is why a plain input goes dead
		if (seated != null) {

			val input = seated.currentInput

			lines += "ctrl modifier: ${input.isSprint}"
			lines += "down: " + inputStates(input).filterValues { it }.keys.sorted().joinToString(", ").ifEmpty { "nothing" }

		}

		return lines

	}

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
		seats[player.uniqueId] = this

		val destination = PositionMoveRotation(Vec3(seatAnchor().x, seatAnchor().y, seatAnchor().z), Vec3.ZERO, 0f, 0f)

		(player as CraftPlayer).handle.connection.teleport(
			destination,
			setOf(Relative.X, Relative.Y, Relative.Z, Relative.Y_ROT, Relative.X_ROT)
		)
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

	/** Stands the pilot up, letting go of what they were holding down and leaving what they had latched. */
	fun vacate() {

		val seated = pilot ?: return

		val mainframe = this.mainframe
		val seatName = mainframe?.nameOf(this)

		if (mainframe != null && seatName != null) mainframe.runtime.release(seatName)

		// the player is gone on a quit, and there is nothing to give back to a player who is not here
		Bukkit.getPlayer(seated)?.let { unlockMovement(it) }

		seats.remove(seated)
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

	/**
	 * Puts a pilot who has ended up somewhere else back on the seat, leaving them their view direction.
	 */
	private fun holdInPlace(player: Player) {

		val anchor = seatAnchor()
		val location = player.location

		if (location.distanceSquared(anchor) <= HOLD_SLACK) return

		// the anchor is where the seat IS, so the position goes across absolutely. only the two rotations
		// are relative, and both deltas are zero, which is what leaves the pilot looking where they were.
		// listing X/Y/Z as relative here would add the seat's world coordinates to the player's own every
		// time this fired, and it fires again the moment they are not on the seat
		val destination = PositionMoveRotation(Vec3(anchor.x, anchor.y, anchor.z), Vec3.ZERO, 0f, 0f)

		(player as CraftPlayer).handle.connection.teleport(
			destination,
			setOf(Relative.Y_ROT, Relative.X_ROT)
		)

	}

	// stood on top of the seat block. sitting inside it (+0.1) puts the pilot in the stair's own geometry,
	// which vanilla spends every tick pushing them back out of
	private fun seatAnchor(): Location = Location(level.world, origin.x + 0.5, origin.y.toDouble(), origin.z + 0.5)

}
