package dev.diena.anion.features.machine.machine_types.debug_furnace

import dev.diena.anion.features.custom.AnionResource
import dev.diena.anion.features.custom.ItemKey
import dev.diena.anion.features.custom.blocks.AnionBlocks
import dev.diena.anion.features.custom.items.AnionItems
import dev.diena.anion.features.machine.BlockSet
import dev.diena.anion.features.machine.component.MachineBuffer
import dev.diena.anion.features.machine.component.MachinePort
import dev.diena.anion.features.machine.machine_types.PortedMachine
import dev.diena.anion.features.recipes.AnionRecipes
import dev.diena.anion.features.recipes.AnionResult
import dev.diena.anion.features.recipes.adapters.MachineRecipeAdapter
import net.minecraft.core.Vec3i
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.block.BlockType

/**
 * Three courses of casing around a single magma cell, cored on the floor. Rows run along x,
 * characters along z, and each slice() is one course up.
 */
val DEBUG_FURNACE_STRUCTURE =
	BlockSet.new("debug_furnace")
		.core('C', AnionBlocks.TEST_BLOCK)

		.assign('I', AnionBlocks.COPPER_MACHINE_CASING)
		.assign('I', AnionBlocks.COPPER_MACHINE_BUS)
		.assign('I', AnionBlocks.COPPER_MACHINE_DISPLAY)
		.assign('I', AnionBlocks.COPPER_MACHINE_DATAPORT)
		.assign('I', AnionBlocks.COPPER_MACHINE_CONDUIT)

		// the hot cell. sealed in, so it is a marker rather than something to interact with
		.assign('M', BlockType.MAGMA_BLOCK)

		.slice(
			"III",
			"ICI",
			"III",
		)
		.slice(
			"III",
			"IMI",
			"III",
		)
		.slice(
			"III",
			"III",
			"III",
		)

		.build()

/**
 * The first machine that actually runs a recipe. Debug: one hardcoded recipe, no fuel value maths,
 * no byproducts, no power.
 *
 * Three buffers, and which port feeds which is the player's to decide — right-click a port block
 * bare-handed to cycle it, `/machine debug` to read the bindings back, `/machine clear <buffer>` to
 * dump one that got loaded with the wrong thing. That is the whole point of the machine: a single
 * buffer can be auto-bound at assembly and never thought about, three cannot.
 *
 * A buffer's capacity is granted by the ports bound to it, so this wants **at least three bus ports**
 * in the casing — with fewer, whichever buffer got none has no room and the furnace stalls against it.
 * That is the framework working as designed rather than a bug, and `/machine debug` shows it as
 * `0/0 ports=0`.
 */
class DebugFurnaceMachine : PortedMachine("Debug Furnace", DEBUG_FURNACE_STRUCTURE) {

	companion object {

		const val FUEL_BUFFER = "fuel"
		const val INPUT_BUFFER = "input"
		const val OUTPUT_BUFFER = "output"

		/** one course above the roof */
		private val SMOKE_OFFSET = Vec3i(0, 3, 0)

		/** per bound port, and the ceiling however many are bound */
		private const val BUFFER_SOFT_CAP = 64L
		private const val BUFFER_HARD_CAP = 512L

	}

	/**
	 * Run-scoped, never the registered adapter.
	 *
	 * A registered recipe is one object shared by every machine that smelts it, and both the adapter's
	 * op counter and the ingredients' progress are mutable — two furnaces sharing them would advance
	 * each other. See [dev.diena.anion.features.recipes.AnionRecipe.newRun].
	 */
	private var currentRun: MachineRecipeAdapter? = null

	/** true on any tick the run actually advanced. drives the smoke and nothing else. */
	var working: Boolean = false; private set

	private var smokePoint: Location? = null

	/**
	 * Which buffer each ingredient is drawn from.
	 *
	 * Deliberately strict: fuel loaded into the input buffer is not quietly found and used, because a
	 * machine that works whatever you do with it teaches nothing about which port is which. Being
	 * stuck is the signal, and spill() is the way out.
	 */
	// lazy so the buffer map is not built while AnionItems is still initialising — AnionMachines
	// constructs one of everything at registration purely to read its key
	private val bufferForIngredient: Map<AnionResource, String> by lazy {
		mapOf(
			ItemKey.of(AnionItems.TEST_FUEL) to FUEL_BUFFER,
			ItemKey.of(AnionItems.TEST_ITEM) to INPUT_BUFFER,
		)
	}

	override fun onAssemble() {

		// declared before super, which resolves the ports and replays their saved bindings — those
		// bindings resolve against this map, so it has to be populated first
		buffers[FUEL_BUFFER] = MachineBuffer(FUEL_BUFFER, ItemKey::class, BUFFER_SOFT_CAP, BUFFER_HARD_CAP)
		buffers[INPUT_BUFFER] = MachineBuffer(INPUT_BUFFER, ItemKey::class, BUFFER_SOFT_CAP, BUFFER_HARD_CAP)
		buffers[OUTPUT_BUFFER] = MachineBuffer(OUTPUT_BUFFER, ItemKey::class, BUFFER_SOFT_CAP, BUFFER_HARD_CAP)

		super.onAssemble()

		bindFreshPorts()

		smokePoint = smokePoint()

	}

	override fun onRelocate() {

		smokePoint = smokePoint()

		super.onRelocate()

	}

	override fun tick() {

		working = false

		val output = buffers[OUTPUT_BUFFER] ?: return

		val adapter = currentRun
			?: MachineRecipeAdapter(AnionRecipes.DEBUG_FURNACE_SMELT.recipe.newRun()).also { currentRun = it }

		val product = (adapter.recipe.result as? AnionResult.Item) ?: return
		val productKey = ItemKey.of(product.item)

		// nowhere to put what it is about to make is a stall, not a loss
		if (!output.accepts(productKey) || output.free() < product.quantity) return

		val result = adapter.tick(
			supply = { resource -> bufferFor(resource)?.amountOf(resource) ?: 0L },
			draw = { resource, units -> bufferFor(resource)?.extract(resource, units) ?: 0L },
		)

		working = result.progressGained > 0.0
		if (working) emitSmoke()

		if (!result.completed) return

		output.insert(productKey, product.quantity.toLong())

		currentRun = null
		markDirty()

	}

	override fun slowTick() {
		// NO-OP
	}

	override fun debugLines(): List<String> {

		val run = currentRun
		val progress = if (run == null) "no run" else "%.0f%%".format(run.progressFraction() * 100)

		return listOf("working=$working run=$progress recipe=${AnionRecipes.DEBUG_FURNACE_SMELT.recipe.displayName}")

	}

	// TODO: an in-flight run is lost on unload — the inputs were already drawn, so the smelt is paid
	//       for and thrown away. saving it needs MachineRecipeAdapter to serialise its op counter and
	//       every ingredient's fed-in progress, which is the adapter's job rather than this machine's.

	/////////////////////
	///// INTERNALS
	/////////////////////

	/** The buffer [resource] is drawn from, or null when nothing on this machine holds that resource. */
	private fun bufferFor(resource: AnionResource): MachineBuffer? =
		buffers[bufferForIngredient[resource] ?: return null]

	/** Spreads never-cycled bus ports over the buffers, so a freshly built furnace does something. */
	// arbitrary on purpose: it is a starting point the player re-cycles, not an inference about intent
	private fun bindFreshPorts() {

		val keys = listOf(INPUT_BUFFER, FUEL_BUFFER, OUTPUT_BUFFER)
		var next = 0

		for (port in ports.values) {

			if (port.kind != MachinePort.Kind.BUS) continue
			if (port.bufferKey != null) continue

			port.bind(keys[next % keys.size])
			next++

		}

	}

	private fun emitSmoke() {

		val point = smokePoint ?: return

		Particle.CAMPFIRE_SIGNAL_SMOKE.builder()
			.location(point)
			.offset(0.0, 1.5, 0.0)
			.count(0)
			.extra(0.1)
			.spawn()

	}

	private fun smokePoint(): Location {

		val pos = localToWorld(SMOKE_OFFSET)

		return Location(
			this.level.world,
			pos.x + 0.5,
			pos.y.toDouble(),
			pos.z + 0.5,
		)

	}

}
