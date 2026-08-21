package dev.diena.anion

import com.example.plugin.Registration
import dev.diena.anion.data.database.AnionDatabase
import dev.diena.anion.data.database.AnionPersistence
import dev.diena.anion.features.custom.blocks.AnionBlocks
import dev.diena.anion.features.custom.items.AnionItemDispatcher
import dev.diena.anion.features.custom.items.AnionItems
import dev.diena.anion.features.machine.AnionMachines
import dev.diena.anion.features.machine.Machine
import dev.diena.anion.features.machine.MachineIndex
import dev.diena.anion.features.starship.Starship
import dev.diena.anion.features.transport.AnionTransport
import dev.diena.anion.features.recipes.AnionRecipes
import io.papermc.paper.plugin.bootstrap.BootstrapContext
import io.papermc.paper.plugin.bootstrap.PluginBootstrap
import io.papermc.paper.plugin.bootstrap.PluginProviderContext
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents.COMMANDS
import org.bukkit.Server
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.TimeUnit

@Suppress("Unused", "UnstableAPIUsage")
class AnionBootstrap : PluginBootstrap {
	val startTime = System.currentTimeMillis()

	override fun bootstrap(context: BootstrapContext) {
		context.lifecycleManager.registerEventHandler(COMMANDS, Registration(context.logger))

		println("[Anion] Bootstrap stage completed in ${(System.currentTimeMillis()-startTime)}ms")
	}

	override fun createPlugin(context: PluginProviderContext): JavaPlugin {
		return Anion()
	}
}

class Anion : JavaPlugin() {

	override fun onEnable() {
		Registration.listeners(this)

		instance = this.server
		plugin = this

		AnionDatabase.open(dataFolder)
		AnionPersistence.rebuildChunkIndices() // no-op unless a pre-index database is being opened

		// init our feature classes that call registries
		AnionItems
		AnionBlocks
		//AnionGasses
		//AnionEnergies
		AnionRecipes
		AnionMachines

		// starship slowTick updates
		Tasks.scheduleAsync(1, 1, TimeUnit.SECONDS, Runnable {
			for ((uuid, ship) in Starship.loadedStarships) {

				// saving
				if (ship.dirty) AnionPersistence.saveStarship(uuid, ship)

				// ticking (sync for API safety)
				Tasks.runSync { ship.slowTick() }
			}
		})

		// machine tick updates (every game tick). sync: tick() reads and writes the world
		Tasks.scheduleSync(0, 1, Runnable {
			for (machine in Machine.activeMachines.values) machine.runTick()
		})

		// machine slowTick updates (once a second). structure re-checks ride the same pass, but only
		// for machines a block change actually queued
		Tasks.scheduleSync(20, 20, Runnable {
			MachineIndex.drainPending()

			for (machine in Machine.activeMachines.values) machine.runSlowTick()
		})

		// held-item readouts, once a second. sync: tools raytrace the world and write the action bar
		Tasks.scheduleSync(20, 20, Runnable {
			AnionItemDispatcher.slowTickEquipped()
		})

		// transport passes. sync: it reads conduit blocks out of the world and writes machine buffers
		Tasks.scheduleSync(10, 10, Runnable {
			AnionTransport.tick()
		})

		// machine snapshots. sync: serialising a machine walks live buffers and its structure map
		Tasks.scheduleSync(20, 20, Runnable {
			for ((uuid, machine) in Machine.activeMachines) {
				if (machine.dirty) AnionPersistence.saveMachine(uuid, machine)
			}
		})

		// and the writes off-thread, since RocksDB has no business on the main thread
		Tasks.scheduleAsync(1, 1, TimeUnit.SECONDS, Runnable {
			AnionPersistence.flushMachineWrites()
		})

	}

	override fun onDisable() {
		AnionPersistence.flushAll()
		AnionDatabase.close()
		Tasks.shutdown()
	}

	companion object {
		const val NAMESPACE = "anion"

		lateinit var instance: Server private set
		lateinit var plugin: Anion private set

	}

}
