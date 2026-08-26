package dev.diena.anion.features.starship

import net.minecraft.core.Vec3i
import java.util.ArrayDeque

/** Detects whether removing a block split a starship into disconnected sections. */
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
		Array(6) { faceIndex -> Vec3i(pos.x + FACE_OFFSETS[faceIndex].x, pos.y + FACE_OFFSETS[faceIndex].y, pos.z + FACE_OFFSETS[faceIndex].z) }

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

		fun find(originIndex: Int): Int {

			var root = originIndex
			while (parent[root] != root) root = parent[root]

			var node = originIndex
			while (parent[node] != node) {

				val next = parent[node]
				parent[node] = root // path compression
				node = next

			}

			return root

		}

		fun union(leftIndex: Int, rightIndex: Int) {

			parent[find(leftIndex)] = find(rightIndex)

		}

		// one frontier per origin, but a single shared claim map so every cell is explored exactly once.
		val owner = HashMap<Vec3i, Int>()
		val queues = Array(origins.size) { ArrayDeque<Vec3i>() }

		for (originIndex in origins.indices) {

			owner[origins[originIndex]] = originIndex
			queues[originIndex].add(origins[originIndex])

		}

		while (true) {

			// stop once only one section still has an open frontier: that survivor is the largest piece
			// (the hull we keep) and needn't be fully scanned; every other section is a complete island.
			val activeRoots = HashSet<Int>()
			for (originIndex in origins.indices) if (queues[originIndex].isNotEmpty()) activeRoots.add(find(originIndex))

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
			for (originIndex in origins.indices) {

				val current = queues[originIndex].poll() ?: continue

				for (neighbour in neighbours(current)) {

					if (neighbour !in blocks) continue

					val claimant = owner[neighbour]
					if (claimant == null) {

						owner[neighbour] = originIndex
						queues[originIndex].add(neighbour)

					} else {

						union(originIndex, claimant)

					}

				}

			}

		}

	}

}
