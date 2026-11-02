package com.magames.tecladoia.spell

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Free LanguageTool public API (https://api.languagetool.org/v2/check, no key).
 * Public limits (2024): 20 requests/min per IP, 75 KB/min, 20 KB per request.
 * We stay well below: one request at most every [MIN_INTERVAL_MS], only after a typing
 * pause, only when the text changed, and only the last [MAX_CHARS] characters.
 */
object LanguageToolClient {
    private const val TAG = "LanguageTool"
    private const val ENDPOINT = "https://api.languagetool.org/v2/check"
    const val MIN_INTERVAL_MS = 4000L
    const val MAX_CHARS = 600

    data class Match(
        val offset: Int,
        val length: Int,
        val message: String,
        val replacements: List<String>,
        val issueType: String,
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastRequestAt = 0L
    @Volatile private var lastText: String? = null
    @Volatile private var backoffUntil = 0L

    fun canRequestNow(): Boolean {
        val now = System.currentTimeMillis()
        return now >= backoffUntil && now - lastRequestAt >= MIN_INTERVAL_MS
    }

    /**
     * Checks [text] asynchronously; [callback] runs on the main thread with the matches
     * (empty on error), or is not called if the request was skipped.
     */
    fun check(text: String, callback: (String, List<Match>) -> Unit): Boolean {
        val t = if (text.length > MAX_CHARS) text.substring(text.length - MAX_CHARS) else text
        if (t.isBlank() || t == lastText || !canRequestNow()) return false
        lastText = t
        lastRequestAt = System.currentTimeMillis()
        executor.execute {
            val result = try {
                request(t)
            } catch (e: Throwable) {
                Log.w(TAG, "LanguageTool failed: ${e.message}")
                null
            }
            if (result != null) main.post { callback(t, result) }
        }
        return true
    }

    private fun request(text: String): List<Match>? {
        val body = "language=pt-BR&enabledOnly=false&text=" + URLEncoder.encode(text, "UTF-8")
        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 8000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "TecladoIA/0.1 (Android keyboard, free)")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code == 429) {
                backoffUntil = System.currentTimeMillis() + 60_000
                return null
            }
            if (code != 200) return null
            val json = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return parse(json)
        } finally {
            conn.disconnect()
        }
    }

    fun parse(json: String): List<Match> {
        val out = ArrayList<Match>()
        val arr = JSONObject(json).optJSONArray("matches") ?: return out
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val reps = m.optJSONArray("replacements")
            val list = ArrayList<String>()
            if (reps != null) for (j in 0 until minOf(reps.length(), 3)) {
                list.add(reps.getJSONObject(j).optString("value"))
            }
            val issue = m.optJSONObject("rule")?.optString("issueType") ?: ""
            out.add(Match(m.optInt("offset"), m.optInt("length"), m.optString("message"), list, issue))
        }
        return out
    }
}
