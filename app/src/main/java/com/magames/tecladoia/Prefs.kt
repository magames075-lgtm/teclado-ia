package com.magames.tecladoia

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("teclado_ia", Context.MODE_PRIVATE)

    /** Never use the network (voice via Vosk only, correction via SymSpell only). */
    var offlineOnly: Boolean
        get() = sp.getBoolean("offline_only", false)
        set(v) = sp.edit().putBoolean("offline_only", v).apply()

    /** Replace a misspelled word with the best suggestion when space is pressed. */
    var autocorrect: Boolean
        get() = sp.getBoolean("autocorrect", false)
        set(v) = sp.edit().putBoolean("autocorrect", v).apply()

    /** Use the free LanguageTool API (grammar + spelling) when online. */
    var onlineCheck: Boolean
        get() = sp.getBoolean("online_check", true)
        set(v) = sp.edit().putBoolean("online_check", v).apply()

    var vibrate: Boolean
        get() = sp.getBoolean("vibrate", true)
        set(v) = sp.edit().putBoolean("vibrate", v).apply()
}
