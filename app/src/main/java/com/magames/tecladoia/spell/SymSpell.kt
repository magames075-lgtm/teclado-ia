package com.magames.tecladoia.spell

import java.text.Normalizer

/**
 * Compact Kotlin port of the SymSpell "symmetric delete" algorithm
 * (original idea and C# implementation by Wolf Garbe, MIT license:
 * https://github.com/wolfgarbe/SymSpell).
 *
 * Memory-friendly variant: instead of a HashMap<String, List<String>> of deletes we keep
 * a sorted LongArray of (hash(delete) << 32 | wordIndex). Hash collisions only add extra
 * candidates, which are then filtered by the real edit distance, so results stay exact.
 */
class SymSpell(
    private val maxEditDistance: Int = 2,
    private val prefixLength: Int = 7,
) {
    data class Suggestion(val term: String, val distance: Double, val count: Long)

    private val words = ArrayList<String>()
    private val counts = ArrayList<Long>()
    private val index = HashMap<String, Int>()
    /** accent-free form -> index of the most frequent word with that form */
    private val bestByFolded = HashMap<String, Int>()
    private var deletes = LongArray(0)
    /** indices sorted alphabetically, for prefix completion */
    private var sortedIdx = IntArray(0)

    val size: Int get() = words.size

    fun addWord(word: String, count: Long) {
        if (word.isEmpty() || index.containsKey(word)) return
        val i = words.size
        words.add(word)
        counts.add(count)
        index[word] = i
        val f = fold(word)
        val prev = bestByFolded[f]
        if (prev == null || counts[prev] < count) bestByFolded[f] = i
    }

    /** Builds the delete index. Call once after all words were added. */
    fun build() {
        var buf = LongArray(words.size * 16)
        var n = 0
        val seen = HashSet<Int>()
        for (i in words.indices) {
            val w = words[i]
            val key = if (w.length > prefixLength) w.substring(0, prefixLength) else w
            seen.clear()
            seen.add(key.hashCode())
            collectDeletes(key, 0, seen)
            for (h in seen) {
                if (n == buf.size) buf = buf.copyOf(buf.size * 2)
                buf[n++] = (h.toLong() shl 32) or i.toLong()
            }
        }
        deletes = buf.copyOf(n)
        deletes.sort()
        sortedIdx = words.indices.sortedBy { words[it] }.toIntArray()
    }

    private fun collectDeletes(s: String, depth: Int, out: HashSet<Int>) {
        if (depth >= maxEditDistance || s.length <= 1) return
        for (p in s.indices) {
            val d = s.removeRange(p, p + 1)
            if (out.add(d.hashCode())) collectDeletes(d, depth + 1, out)
            else if (depth + 1 < maxEditDistance) collectDeletes(d, depth + 1, out)
        }
    }

    fun contains(word: String) = index.containsKey(word)
    fun count(word: String): Long = index[word]?.let { counts[it] } ?: 0L

    /**
     * When the user types an accent-less form ("nao", "voce") that is far less common than
     * its accented variant ("não", "você"), return the accented variant.
     */
    fun accentVariant(word: String, ratio: Double = 30.0): String? {
        val best = bestByFolded[fold(word)] ?: return null
        val bestWord = words[best]
        if (bestWord == word) return null
        val mine = count(word)
        return if (mine == 0L || counts[best] >= mine * ratio) bestWord else null
    }

    fun lookup(input: String, max: Int = 3): List<Suggestion> {
        if (input.isEmpty()) return emptyList()
        val key = if (input.length > prefixLength) input.substring(0, prefixLength) else input
        val hashes = HashSet<Int>()
        hashes.add(key.hashCode())
        collectDeletes(key, 0, hashes)
        val candidates = HashSet<Int>()
        for (h in hashes) {
            val target = h.toLong() shl 32
            var pos = lowerBound(target)
            while (pos < deletes.size && (deletes[pos] ushr 32).toInt() == h) {
                candidates.add((deletes[pos] and 0xffffffffL).toInt())
                pos++
            }
        }
        val foldedInput = fold(input)
        val out = ArrayList<Suggestion>()
        for (c in candidates) {
            val w = words[c]
            if (kotlin.math.abs(w.length - input.length) > maxEditDistance) continue
            var d = osaDistance(input, w, maxEditDistance).toDouble()
            if (d < 0) continue
            if (fold(w) == foldedInput) d = 0.5 // only accents differ: strong candidate
            out.add(Suggestion(w, d, counts[c]))
        }
        out.sortWith(compareBy<Suggestion> { it.distance }.thenByDescending { it.count })
        return out.take(max)
    }

    /** Most frequent words that start with [prefix] (excluding the prefix itself). */
    fun complete(prefix: String, max: Int = 3): List<String> {
        if (prefix.length < 2 || sortedIdx.isEmpty()) return emptyList()
        var lo = 0
        var hi = sortedIdx.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (words[sortedIdx[mid]] < prefix) lo = mid + 1 else hi = mid
        }
        val found = ArrayList<Int>()
        var i = lo
        while (i < sortedIdx.size && words[sortedIdx[i]].startsWith(prefix)) {
            if (words[sortedIdx[i]] != prefix) found.add(sortedIdx[i])
            i++
            if (found.size > 400) break
        }
        return found.sortedByDescending { counts[it] }.take(max).map { words[it] }
    }

    private fun lowerBound(target: Long): Int {
        var lo = 0
        var hi = deletes.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (deletes[mid] < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        private val MARKS = Regex("\\p{Mn}+")

        fun fold(s: String): String =
            MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "")

        /** Optimal string alignment distance; returns -1 if greater than [max]. */
        fun osaDistance(a: String, b: String, max: Int): Int {
            if (a == b) return 0
            val n = a.length
            val m = b.length
            if (kotlin.math.abs(n - m) > max) return -1
            var prev2 = IntArray(m + 1)
            var prev = IntArray(m + 1) { it }
            var cur = IntArray(m + 1)
            for (i in 1..n) {
                cur[0] = i
                var rowMin = cur[0]
                for (j in 1..m) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                    if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                        v = minOf(v, prev2[j - 2] + 1)
                    }
                    cur[j] = v
                    if (v < rowMin) rowMin = v
                }
                if (rowMin > max) return -1
                val t = prev2; prev2 = prev; prev = cur; cur = t
            }
            return if (prev[m] <= max) prev[m] else -1
        }
    }
}
