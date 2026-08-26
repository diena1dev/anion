package dev.diena.anion.features.machine.component

import dev.diena.anion.Tasks
import dev.diena.anion.features.machine.Machine
import net.kyori.adventure.text.Component
import net.minecraft.core.Vec3i
import net.minecraft.server.level.ServerLevel
import org.bukkit.DyeColor
import org.bukkit.block.Sign
import org.bukkit.block.sign.Side

/** A sign that is part of a machine's structure. */
object MachineSign {

	/** The machine whose structure holds the sign at [cell], and where that sign sits on it. */
	fun ownerAt(level: ServerLevel, cell: Vec3i): Pair<Machine, Vec3i>? {

		val block = level.world.getBlockAt(cell.x, cell.y, cell.z)
		if (block.state !is Sign) return null

		for (machine in Machine.machinesAt(level, cell)) {

			val offset = machine.resolvedStructure.keys.firstOrNull { machine.localToWorld(it) == cell } ?: continue

			return machine to offset

		}

		return null

	}

	/** Writes [lines] onto the sign at [offset]. No-op while [machine] is broken. Run sync. */
	fun write(machine: Machine, offset: Vec3i, lines: List<Component>, glow: Boolean = false, front: Boolean = true) {

		Tasks.runSync {

			// checked in here rather than at the call: the write is deferred, so a machine that was intact
			// when it was queued can be rubble by the time it lands
			if (!machine.intact) return@runSync

			val cell = machine.localToWorld(offset)
			val block = machine.level.world.getBlockAt(cell.x, cell.y, cell.z)
			val sign = block.state as? Sign ?: return@runSync

			val side = sign.getSide(if (front) Side.FRONT else Side.BACK)
			for (index in 0..3) side.line(index, lines.getOrElse(index) { Component.empty() })

			side.isGlowingText = true
			side.color = DyeColor.GREEN
			sign.isWaxed = true // a waxed sign is uneditable in vanilla, which is most of the job done
			sign.update(true, false)

		}

	}

	/** Waxes every sign in [machine]'s structure, so none of them can be typed into. */
	fun seal(machine: Machine) {

		Tasks.runSync {

			if (!machine.intact) return@runSync

			for (offset in machine.resolvedStructure.keys) {

				val cell = machine.localToWorld(offset)
				val block = machine.level.world.getBlockAt(cell.x, cell.y, cell.z)
				val sign = block.state as? Sign ?: continue

				if (sign.isWaxed) continue

				sign.isWaxed = true
				sign.update(true, false)

			}

		}

	}

}
