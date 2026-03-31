package de.majuwa.android.paper.krhnlesimagemanagement.util

import android.graphics.Bitmap
import android.graphics.Color
import de.majuwa.android.paper.krhnlesimagemanagement.model.RemotePhoto

/**
 * Lightweight duplicate detection using Difference Hashing (dHash).
 *
 * Each image is squashed to 9×8 pixels and turned into a 64-bit fingerprint by comparing
 * adjacent pixel brightness. Two images are considered duplicates when their Hamming distance
 * is ≤ [SIMILARITY_THRESHOLD].
 */
object DuplicateFinder {
    private const val HASH_COLS = 9 // 9 wide → 8 left/right comparisons per row
    private const val HASH_ROWS = 8
    const val SIMILARITY_THRESHOLD = 18

    /** Produces a 64-bit dHash fingerprint from [bitmap]. Recycles the scaled intermediate. */
    fun computeDHash(bitmap: Bitmap): Long {
        val resized = Bitmap.createScaledBitmap(bitmap, HASH_COLS, HASH_ROWS, true)
        var hash = 0L
        for (y in 0 until HASH_ROWS) {
            for (x in 0 until HASH_ROWS) { // 8 comparisons: x in 0..7, accessing x and x+1
                val grayLeft = luminance(resized.getPixel(x, y))
                val grayRight = luminance(resized.getPixel(x + 1, y))
                if (grayLeft > grayRight) {
                    hash = hash or (1L shl (y * HASH_ROWS + x))
                }
            }
        }
        resized.recycle()
        return hash
    }

    /** Number of bits that differ between two hashes (lower = more similar). */
    fun hammingDistance(
        h1: Long,
        h2: Long,
    ): Int = java.lang.Long.bitCount(h1 xor h2)

    /**
     * Groups [photos] into sets of near-duplicates using an all-pairs Union-Find.
     *
     * Every photo is compared against every other photo. For typical album sizes the
     * comparison is a simple Long XOR + bitcount per pair, which is negligible even
     * for hundreds of photos. A sliding-window approach was tried first but missed
     * duplicates whose filenames sort far apart on the server.
     *
     * Returns only groups with ≥ 2 members.
     */
    fun groupDuplicates(
        photos: List<RemotePhoto>,
        hashes: Map<String, Long>,
        threshold: Int = SIMILARITY_THRESHOLD,
    ): List<List<RemotePhoto>> {
        val n = photos.size
        val uf = UnionFind(n)

        for (i in 0 until n) {
            val hi = hashes[photos[i].href] ?: continue
            for (j in i + 1 until n) {
                val hj = hashes[photos[j].href] ?: continue
                if (hammingDistance(hi, hj) <= threshold) {
                    uf.union(i, j)
                }
            }
        }

        return uf
            .groups()
            .values
            .filter { it.size >= 2 }
            .map { indices -> indices.map { photos[it] } }
    }

    private fun luminance(pixel: Int): Int =
        (Color.red(pixel) * 0.299 + Color.green(pixel) * 0.587 + Color.blue(pixel) * 0.114)
            .toInt()

    private class UnionFind(
        size: Int,
    ) {
        private val parent = IntArray(size) { it }

        fun find(x: Int): Int {
            var root = x
            while (parent[root] != root) root = parent[root]
            // Iterative path compression
            var curr = x
            while (curr != root) {
                val next = parent[curr]
                parent[curr] = root
                curr = next
            }
            return root
        }

        fun union(
            x: Int,
            y: Int,
        ) {
            parent[find(x)] = find(y)
        }

        fun groups(): Map<Int, List<Int>> = (parent.indices).groupBy { find(it) }
    }
}
