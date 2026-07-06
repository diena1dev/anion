package dev.diena.anion.features.machine

import dev.diena.anion.features.custom.blocks.AnionBlock
import net.minecraft.core.BlockPos

/**
 * Structure of a Machine! Defines the bounds and the origin point of the Machine's structure.
 * The origin point is used in when assembling a Machine with a wrench, usually occupied by a Machine "Core" tiered block.
 * */
class MachineStructure(
    val blockMap: MutableMap<BlockPos, AnionBlock>,
    height: Int,
    width: Int,
    depth: Int
) {

    /** holy FUCK astral is so much smarter than i am */



}
