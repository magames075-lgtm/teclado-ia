package com.magames.tecladoia

import android.app.Application
import com.magames.tecladoia.spell.SpellEngine
import com.magames.tecladoia.voice.VoskModelManager

class TecladoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SpellEngine.init(this)
        // "Completo" build: unpack the bundled offline voice model once, in the background.
        VoskModelManager.ensureInstalledFromAssets(this)
    }
}
