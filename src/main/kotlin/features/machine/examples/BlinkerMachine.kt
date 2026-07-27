package dev.diena.anion.features.machine.examples

import dev.diena.anion.features.custom.blocks.AnionBlocks
import dev.diena.anion.features.machine.BlockSet
import dev.diena.anion.features.machine.Machine
import net.minecraft.core.Vec3i
import org.bukkit.block.BlockType
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Lightable

val BLINKER_MACHINE =
    BlockSet.new("blinker_machine")
        .core('C', AnionBlocks.URANIUM_BLOCK)
        .assign('I', AnionBlocks.COPPER_MACHINE_CASING)
        .assign('B', BlockType.IRON_BLOCK)

        .slice(
            "   ",
            " C ",
            "   "
        ) // base y
        .slice(
            "   ",
            " I ",
            "   "
        )
        .slice(
            "   ",
            " I ",
            "   "
        )
        .slice(
            " B ",
            "BIB",
            " B "
        )

        .build()

class BlinkerMachine : Machine("Blinker", BLINKER_MACHINE) {

    private val lampOffset = Vec3i(0, 4, 0)
    private var ticksSinceBlink = 0

    var lampData: BlockData = BlockType.REDSTONE_LAMP.createBlockData().createBlockState().blockData

	override fun tick() {
        ticksSinceBlink++
    }

    override fun slowTick() {
        ticksSinceBlink = 0

        val lightableData = (this.lampData as Lightable)
        lightableData.isLit = !lightableData.isLit
        lampData = lightableData

        this.setBlockAt(lampOffset, lampData)

        this.markDirty()
    }

}
