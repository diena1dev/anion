package dev.diena.anion.features.machine.component

import dev.diena.anion.features.custom.AnionResource
import kotlin.reflect.KClass

/**
 * A single resource store on a Machine. Holds one [AnionResource] at a time, and only resources of
 * the type it was declared with.
 *
 * @param key           name this buffer is addressed by within its machine
 * @param resourceType  resource family this buffer accepts (AnionItem, AnionGas, ...)
 * @param softCap       capacity granted per bound port
 * @param hardCap       ceiling across every bound port, no matter how many are attached
 */
open class MachineBuffer(

	val key: String,
	val resourceType: KClass<out AnionResource>,
	val softCap: Long,
	val hardCap: Long,

) {

	var resource: AnionResource? = null; protected set
	var amount: Long = 0L; protected set

	/** ports bound to this buffer. capacity scales with the count, up to [hardCap]. */
	val boundPorts: MutableSet<MachinePort> = mutableSetOf()

	/** Current capacity: [softCap] per bound port, clamped to [hardCap]. */
	open fun capacity(): Long = minOf(softCap * boundPorts.size, hardCap)

	/** Inserts up to [units] of [resource]. Returns how much was accepted. */
	open fun insert(resource: AnionResource, units: Long): Long {

		if (units <= 0L) return 0L
		if (!resourceType.isInstance(resource)) return 0L

		val held = this.resource
		if (held != null && held.namespacedKey != resource.namespacedKey) return 0L // one resource at a time

		val accepted = minOf(units, capacity() - amount).coerceAtLeast(0L)
		if (accepted == 0L) return 0L

		this.resource = resource
		this.amount += accepted

		return accepted

	}

	/** Removes up to [units]. Returns how much was actually drawn. */
	open fun extract(units: Long): Long {

		if (units <= 0L) return 0L

		val drawn = minOf(units, amount)
		amount -= drawn
		if (amount == 0L) resource = null

		return drawn

	}

	/** Drops the whole contents. Called when the machine is disassembled. */
	open fun clear() {

		resource = null
		amount = 0L

	}

	/** Restores a saved buffer. Bypasses [capacity] — ports are bound after state is read back. */
	internal fun restore(resource: AnionResource?, amount: Long) {

		this.resource = resource
		this.amount = amount

	}

}
