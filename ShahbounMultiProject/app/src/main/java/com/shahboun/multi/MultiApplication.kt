package com.shahboun.multi

import android.app.Application

class MultiApplication : Application() {
    lateinit var engine: ShahbounCloneEngine
        private set

    override fun onCreate() {
        super.onCreate()
        engine = ShahbounCloneEngine()
        engine.initialize(this).getOrThrow()
        RuntimeInstrumentationInstaller.install().getOrThrow()
    }
}
