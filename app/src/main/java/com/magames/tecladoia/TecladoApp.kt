package com.magames.tecladoia

import android.app.Application
import com.magames.tecladoia.spell.SpellEngine

class TecladoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SpellEngine.init(this)
    }
}
