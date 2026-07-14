package dev.diena.anion

import com.example.plugin.Registration
import dev.diena.anion.data.database.AnionDatabase
import dev.diena.anion.data.database.AnionPersistence
import dev.diena.anion.features.custom.blocks.AnionBlocks
import dev.diena.anion.features.custom.items.AnionItems
import dev.diena.anion.features.machine.MachineStructures
import dev.diena.anion.features.starship.Starship
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

        // init our feature classes that call registries
        AnionItems
        AnionBlocks
        //AnionGasses
        //AnionEnergies
        MachineStructures
	    AnionRecipes

        // starship slowTick updates
        Tasks.scheduleAsync(1, 1, TimeUnit.SECONDS, Runnable {
            for ((uuid, ship) in Starship.loadedStarships) {

                // saving
                if (ship.dirty) AnionPersistence.saveStarship(uuid, ship)

                // ticking (sync for API safety)
                Tasks.runSync { ship.slowTick() }
            }
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
