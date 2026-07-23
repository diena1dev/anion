package dev.diena.anion

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.logging.Level

@Suppress("Unused")
object Tasks {

    private val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(4)

    fun runSync(block: Runnable) {
        Anion.instance.scheduler.runTask(Anion.plugin, block)
    }

    fun runAsync(block: Runnable): ScheduledFuture<*> {
        return executor.schedule(block.logExceptions(), 0, TimeUnit.MILLISECONDS)
    }

    /** [ScheduledExecutorService.scheduleAtFixedRate] silently and permanently stops future executions
     *  if [block] ever throws — no log, no crash, it just never runs again. wrap so a single bad tick
     *  logs and gets skipped instead of killing the whole periodic task forever. */
    fun scheduleAsync(
        delay: Long,
        period: Long,
        unit: TimeUnit,
        block: Runnable
    ): ScheduledFuture<*> {
        return executor.scheduleAtFixedRate(block.logExceptions(), delay, period, unit)
    }

    fun scheduleAsyncDelayed(
        delay: Long,
        unit: TimeUnit,
        block: Runnable
    ): ScheduledFuture<*> {
        return executor.schedule(block.logExceptions(), delay, unit)
    }

    fun shutdown() {
        executor.shutdown()
    }

    private fun Runnable.logExceptions(): Runnable = Runnable {
        try {
            run()
        } catch (e: Throwable) {
            Anion.plugin.logger.log(Level.SEVERE, "Uncaught exception in scheduled task", e)
        }
    }

}
