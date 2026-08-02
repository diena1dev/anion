package dev.diena.anion.features.starship

import net.minecraft.core.Vec3i
import java.util.ArrayDeque

/**
 * Detects whether removing a block split a starship into disconnected sections.
 */
object StarshipSplit {

    private val FACE_OFFSETS = arrayOf(
        Vec3i(1, 0, 0),
        Vec3i(-1, 0, 0),
        Vec3i(0, 1, 0),
        Vec3i(0, -1, 0),
        Vec3i(0, 0, 1),
        Vec3i(0, 0, -1),
    )

    private fun neighbours(pos: Vec3i): Array<Vec3i> =
        Array(6) { i -> Vec3i(pos.x + FACE_OFFSETS[i].x, pos.y + FACE_OFFSETS[i].y, pos.z + FACE_OFFSETS[i].z) }

    /**
     * @param blocks     immutable snapshot of every position on the ship (removed block already gone).
     * @param removedPos position of the block that was just removed.
     * @return one position-set per detached section (the pieces that should become their own ships);
     *         empty if the ship is still one connected piece, or a split was impossible.
     */
    fun detachedComponents(blocks: Set<Vec3i>, removedPos: Vec3i): List<Set<Vec3i>> {

        // origins are ship blocks that were touching the removed block. a split can only exist between these.
        val origins = neighbours(removedPos).filter { it in blocks }
        if (origins.size < 2) return emptyList() // 0 or 1 neighbor: nothing could have been split apart

        // union-find over origin indices these are origins whose frontiers meet belong to the same section.
        // frontiers are the edges of the BFS search!
        val parent = IntArray(origins.size) { it }

        fun find(x: Int): Int {

            var root = x
            while (parent[root] != root) root = parent[root]

            var node = x
            while (parent[node] != node) {

                val next = parent[node]
                parent[node] = root // path compression
                node = next

            }

            return root

        }

        fun union(a: Int, b: Int) {

            parent[find(a)] = find(b)

        }

        // one frontier per origin, but a single shared claim map so every cell is explored exactly once.
        val owner = HashMap<Vec3i, Int>()
        val queues = Array(origins.size) { ArrayDeque<Vec3i>() }

        for (i in origins.indices) {

            owner[origins[i]] = i
            queues[i].add(origins[i])

        }

        while (true) {

            // stop once only one section still has an open frontier: that survivor is the largest piece
            // (the hull we keep) and needn't be fully scanned; every other section is a complete island.
            val activeRoots = HashSet<Int>()
            for (i in origins.indices) if (queues[i].isNotEmpty()) activeRoots.add(find(i))

            if (activeRoots.size <= 1) {

                // group every claimed cell by its final section.
                val groups = HashMap<Int, MutableSet<Vec3i>>()
                for ((cell, origin) in owner) groups.getOrPut(find(origin)) { HashSet() }.add(cell)

                // the main hull is the lone still-active root, or (if everything got explored) the biggest group. every other group is a detached section.
                val mainRoot = activeRoots.firstOrNull()
                    ?: groups.maxByOrNull { it.value.size }?.key
                    ?: return emptyList()

                return groups.filterKeys { it != mainRoot }.values.toList()

            }

            // advance every open frontier by a single node.
            for (i in origins.indices) {

                val current = queues[i].poll() ?: continue

                for (n in neighbours(current)) {

                    if (n !in blocks) continue

                    val claimant = owner[n]
                    if (claimant == null) {

                        owner[n] = i
                        queues[i].add(n)

                    } else {

                        union(i, claimant)

                    }

                }

            }

        }

    }

}
