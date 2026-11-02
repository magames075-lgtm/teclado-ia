package com.magames.tecladoia.spell

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Offline pt-BR spell checker built on SymSpell + the bundled FrequencyWords pt_br 50k list.
 * Loads in the background; until it is ready every word is treated as correct.
 */
object SpellEngine {
    private const val TAG = "SpellEngine"
    private val ptBR = Locale("pt", "BR")

    @Volatile var ready = false
        private set
    private var sym = SymSpell()
    private val executor = Executors.newSingleThreadExecutor()

    /** Single letters / tiny words that are valid in Portuguese. */
    private val shortOk = setOf("a", "à", "e", "é", "o", "ó", "ô", "u", "y")

    data class Check(
        val word: String,
        val misspelled: Boolean,
        val suggestions: List<String>,
        /** Best replacement for autocorrect, or null if not confident. */
        val autoFix: String?,
    )

    fun init(context: Context, onReady: (() -> Unit)? = null) {
        if (ready) { onReady?.invoke(); return }
        val app = context.applicationContext
        executor.execute {
            if (ready) { onReady?.invoke(); return@execute }
            val t0 = System.currentTimeMillis()
            val s = SymSpell()
            try {
                app.assets.open("pt_br_50k.txt").use { input ->
                    BufferedReader(InputStreamReader(input, Charsets.UTF_8)).forEachLine { line ->
                        val sp = line.lastIndexOf(' ')
                        if (sp <= 0) return@forEachLine
                        val w = line.substring(0, sp).trim().lowercase(ptBR)
                        val c = line.substring(sp + 1).trim().toLongOrNull() ?: return@forEachLine
                        if (!isWordLike(w)) return@forEachLine
                        if (w.length == 1 && w !in shortOk) return@forEachLine
                        s.addWord(w, c)
                    }
                }
                s.build()
                sym = s
                ready = true
                Log.i(TAG, "Dictionary ready: ${s.size} words in ${System.currentTimeMillis() - t0} ms")
            } catch (e: Throwable) {
                Log.e(TAG, "Dictionary load failed", e)
            }
            onReady?.invoke()
        }
    }

    private fun isWordLike(w: String): Boolean =
        w.isNotEmpty() && w.all { it.isLetter() || it == '-' } && w.first().isLetter()

    /**
     * Checks one word as typed. Case is preserved in the suggestions
     * ("Voce" -> "Você", "VOCE" -> "VOCÊ").
     */
    fun check(typed: String): Check {
        if (!ready || typed.isEmpty() || !isWordLike(typed)) return Check(typed, false, emptyList(), null)
        val lower = typed.lowercase(ptBR)
        if (sym.contains(lower)) {
            val accent = sym.accentVariant(lower)
            if (accent != null) {
                val sugg = listOf(matchCase(typed, accent))
                return Check(typed, true, sugg, sugg[0])
            }
            val completions = sym.complete(lower, 3).map { matchCase(typed, it) }
            return Check(typed, false, completions, null)
        }
        // Hyphenated words: accept if every part is known ("guarda-chuva", "fazê-lo")
        if (lower.contains('-') && lower.split('-').all { it.isEmpty() || sym.contains(it) || it in pronouns }) {
            return Check(typed, false, emptyList(), null)
        }
        val res = sym.lookup(lower, 3)
        val suggestions = res.map { matchCase(typed, it.term) }
        val auto = res.firstOrNull()?.let { best ->
            val second = res.getOrNull(1)
            val confident = best.distance <= 1.0 ||
                (best.distance <= 2.0 && lower.length >= 5 &&
                    (second == null || second.distance > best.distance || best.count > second.count * 5))
            if (confident) matchCase(typed, best.term) else null
        }
        // Unknown word with no suggestion (names, slang): do not flag it as an error.
        return Check(typed, suggestions.isNotEmpty(), suggestions, auto)
    }

    private val pronouns = setOf("lo", "la", "los", "las", "no", "na", "nos", "nas", "me", "te", "se", "lhe", "lhes")

    fun matchCase(typed: String, word: String): String = when {
        typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase(ptBR)
        typed.firstOrNull()?.isUpperCase() == true ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(ptBR) else it.toString() }
        else -> word
    }
}
