package dev.diena.anion.features.machine.component

import dev.diena.anion.features.custom.AnionResource
import kotlin.reflect.KClass

/**
 * A resource store on a Machine. The base holds **one** resource at a time — a gas buffer that took
 * oxygen refuses hydrogen until it is drained — which is what [accepts] encodes.
 *
 * Two gates stack: [resourceType] is the family gate (is this an AnionGas at all), [accepts] is the
 * instance gate (is it *this* gas). Subclasses that hold several resources at once, like a bulk
 * container, override [accepts] and the storage trio and leave the family gate alone.
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

	/** What this buffer holds. Never more than one entry in the base — a subclass may hold more. */
	open fun contents(): Map<AnionResource, Long> {

		val held = resource ?: return emptyMap()

		return mapOf(held to amount)

	}

	/** Units of [resource] on hand. */
	open fun amountOf(resource: AnionResource): Long = contents()[resource] ?: 0L

	/** Whether [resource] may be put in at all, ignoring how much room is left. */
	// the one-resource-at-a-time rule lives here so a subclass can widen it without touching insert().
	open fun accepts(resource: AnionResource): Boolean {

		if (!resourceType.isInstance(resource)) return false

		val held = this.resource

		return held == null || held == resource

	}

	/** Current capacity: [softCap] per bound port, clamped to [hardCap]. */
	// throughput scales with ports because that is what a machine input is. storage that scales with
	// volume instead should override this outright.
	open fun capacity(): Long = minOf(softCap * boundPorts.size, hardCap)

	/** Total units held across every resource. */
	open fun used(): Long = contents().values.sum()

	/** Room left before [capacity] is hit. */
	open fun free(): Long = (capacity() - used()).coerceAtLeast(0L)

	/** Inserts up to [units] of [resource]. Returns how much was accepted. */
	open fun insert(resource: AnionResource, units: Long): Long {

		if (units <= 0L) return 0L
		if (!accepts(resource)) return 0L

		val accepted = minOf(units, free())
		if (accepted <= 0L) return 0L

		this.resource = resource
		this.amount += accepted

		return accepted

	}

	/** Removes up to [units] of [resource]. Returns how much was actually drawn. */
	// resource is named even though the base only ever holds one: a recipe asking for hydrogen must
	// get nothing from a buffer that is holding oxygen, rather than silently drawing the wrong gas.
	open fun extract(resource: AnionResource, units: Long): Long {

		if (units <= 0L) return 0L
		if (this.resource != resource) return 0L

		val drawn = minOf(units, amount)
		amount -= drawn
		if (amount == 0L) this.resource = null

		return drawn

	}

	/** Drops the whole contents. Called when the machine is disassembled. */
	open fun clear() {

		resource = null
		amount = 0L

	}

	/** Restores a saved buffer. Bypasses [capacity] — ports are bound after state is read back. */
	internal open fun restore(contents: Map<AnionResource, Long>) {

		val (held, units) = contents.entries.firstOrNull() ?: run {
			clear()
			return
		}

		this.resource = held
		this.amount = units

	}

}
