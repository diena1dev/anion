package dev.diena.anion.features.machine.component

import dev.diena.anion.features.custom.AnionResource
import dev.diena.anion.features.custom.ItemKey
import dev.diena.anion.features.machine.Machine
import kotlin.reflect.KClass

/**
 * A resource store on a Machine. The base holds **one** resource at a time — a gas buffer that took
 * oxygen refuses hydrogen until it is drained — which is what [accepts] encodes.
 *
 * Two gates stack: [resourceType] is the family gate (is this an AnionGas at all), [accepts] is the
 * instance gate (is it *this* gas). Subclasses that hold several resources at once, like a bulk
 * container, override [accepts] and the storage trio and leave the family gate alone.
 *
 * Capacity is fixed. Ports move resources in and out; they do not make room for them, so bolting more
 * onto the casing makes a machine faster and never bigger.
 *
 * @param key             name this buffer is addressed by within its machine
 * @param resourceType    resource family this buffer accepts (AnionItem, AnionGas, ...)
 * @param capacity        how much it holds, whatever is bound to it
 * @param transferPerPort units moved per transport pass, for each port bound to this buffer
 */
open class MachineBuffer(

	val key: String,
	val resourceType: KClass<out AnionResource>,
	val capacity: Long,
	val transferPerPort: Long = DEFAULT_TRANSFER_PER_PORT,
	/**
	 * Whether a player may dump this buffer with a screwdriver.
	 *
	 * False for bulk storage, where emptying it is the opposite of the point and a full one is
	 * thousands of items on the floor. Disassembly ignores this — the machine is going away either way.
	 */
	val spillable: Boolean = true,

) {

	companion object {

		/** what one port is worth per pass when a buffer does not say otherwise */
		const val DEFAULT_TRANSFER_PER_PORT = 64L

	}

	// the base's own storage. a subclass overriding contents() holds its items elsewhere and leaves
	// these untouched, so nothing outside should read them — go through contents()/amountOf().
	protected var resource: AnionResource? = null
	protected var amount: Long = 0L

	/** ports bound to this buffer. throughput scales with the count, capacity does not. */
	val boundPorts: MutableSet<MachinePort> = mutableSetOf()

	/**
	 * The machine that declared this buffer. Claimed by [Machine] once the type has declared its
	 * buffers, so anything that changes what is held marks that machine for saving.
	 *
	 * Null until then, and deliberately null while [restore] runs — writing back what is already on
	 * disk is not a change.
	 */
	internal var owner: Machine? = null

	/** Marks the owning machine for saving. Every path that changes what is held goes through this. */
	// a machine is only written when it is dirty, so a buffer that fills without saying so is items
	// that exist until the next restart and then do not
	protected fun markChanged() {
		owner?.dirty = true
	}

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

	/**
	 * Units this buffer will take in or give up across one transport pass, however many drivers are
	 * pushing at it.
	 *
	 * Scales with bound ports because that is the whole point of ports — a machine fed through seven
	 * of them should genuinely run seven times harder. What stops it scaling forever is the machine's
	 * own [Machine.transferCeiling], not anything here: the ceiling is shared across every buffer, so
	 * a wall of ports cannot be spread over several of them to dodge it.
	 */
	open fun transferLimit(): Long = (transferPerPort * boundPorts.size).coerceAtLeast(0L)

	/** Total units held across every resource. */
	open fun used(): Long = contents().values.sum()

	/** Room left before [capacity] is hit. */
	open fun free(): Long = (capacity - used()).coerceAtLeast(0L)

	/** Inserts up to [units] of [resource]. Returns how much was accepted. */
	open fun insert(resource: AnionResource, units: Long): Long {

		if (units <= 0L) return 0L
		if (!accepts(resource)) return 0L

		val accepted = minOf(units, free())
		if (accepted <= 0L) return 0L

		this.resource = resource
		this.amount += accepted
		markChanged()

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
		if (drawn > 0L) markChanged()

		return drawn

	}

	/** One line for a debug readout. */
	open fun describe(): String = "$key ${used()}/$capacity ports=${boundPorts.size} rate=${transferLimit()}/pass"

	/** Drops the whole contents. Called when the machine is disassembled. */
	open fun clear() {

		if (resource != null || amount != 0L) markChanged()

		resource = null
		amount = 0L

	}

	/** Restores a saved buffer. Bypasses [capacity] so lowering one never voids what was already in it. */
	internal open fun restore(contents: Map<AnionResource, Long>) {

		val (held, units) = contents.entries.firstOrNull() ?: run {
			clear()
			return
		}

		this.resource = held
		this.amount = units

	}

}

/**
 * Item store that holds several variants at once: up to [typeLimit] distinct ones, and [capacity]
 * items summed across all of them. Two caps, and they bite independently — a container with room by
 * count still refuses a variant it has no type slot for.
 *
 * Variants are [ItemKey]s, so a damaged tool and a pristine one occupy two type slots.
 */
open class BulkItemBuffer(

	key: String,
	val typeLimit: Int,
	capacity: Long,
	transferPerPort: Long = DEFAULT_TRANSFER_PER_PORT,

) : MachineBuffer(

	key,
	ItemKey::class,
	capacity,
	transferPerPort,
	// dumping a container is the opposite of what one is for, and a full one is 24000 items on the floor
	spillable = false,

) {

	// insertion-ordered so a readout lists things in the order they first arrived
	private val stored: MutableMap<AnionResource, Long> = LinkedHashMap()

	override fun contents(): Map<AnionResource, Long> = stored

	/** Distinct variants held, against [typeLimit]. */
	fun typesUsed(): Int = stored.size

	override fun describe(): String =
		"$key ${used()}/$capacity ${typesUsed()}/$typeLimit types ports=${boundPorts.size} rate=${transferLimit()}/pass"

	override fun accepts(resource: AnionResource): Boolean {

		if (resource !is ItemKey) return false

		return stored.containsKey(resource) || stored.size < typeLimit

	}

	override fun insert(resource: AnionResource, units: Long): Long {

		if (units <= 0L) return 0L
		if (!accepts(resource)) return 0L

		val accepted = minOf(units, free())
		if (accepted <= 0L) return 0L

		stored[resource] = (stored[resource] ?: 0L) + accepted
		markChanged()

		return accepted

	}

	override fun extract(resource: AnionResource, units: Long): Long {

		if (units <= 0L) return 0L

		val held = stored[resource] ?: return 0L
		val drawn = minOf(units, held)

		// drop the entry outright at zero, otherwise it would hold a type slot hostage
		if (drawn == held) stored.remove(resource) else stored[resource] = held - drawn
		if (drawn > 0L) markChanged()

		return drawn

	}

	override fun clear() {

		if (stored.isNotEmpty()) markChanged()

		stored.clear()

	}

	override fun restore(contents: Map<AnionResource, Long>) {

		stored.clear()
		stored.putAll(contents)

	}

}
