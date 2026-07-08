package dev.diena.anion

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@Suppress("Unused")
object Tasks {

    private val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(4)

    fun runSync(block: Runnable) {
        Anion.instance.scheduler.runTask(Anion.plugin, block)
    }

    fun runAsync(block: Runnable): ScheduledFuture<*> {
        return executor.schedule(block, 0, TimeUnit.MILLISECONDS)
    }

    fun scheduleAsync(
        delay: Long,
        period: Long,
        unit: TimeUnit,
        block: Runnable
    ): ScheduledFuture<*> {
        return executor.scheduleAtFixedRate(block, delay, period, unit)
    }

    fun scheduleAsyncDelayed(
        delay: Long,
        unit: TimeUnit,
        block: Runnable
    ): ScheduledFuture<*> {
        return executor.schedule(block, delay, unit)
    }

    fun shutdown() {
        executor.shutdown()
    }

}
