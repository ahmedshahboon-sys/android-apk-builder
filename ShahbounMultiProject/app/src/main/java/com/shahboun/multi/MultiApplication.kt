package com.shahboun.multi

import android.app.Application

class MultiApplication : Application() {
    lateinit var engine: CloneEngine
        private set

    override fun onCreate() {
        super.onCreate()
        engine = ShahbounCloneEngine()
        engine.initialize(this).getOrThrow()
    }
}
