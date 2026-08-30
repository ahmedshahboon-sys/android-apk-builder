package com.shahboun.multi

import android.app.Application

class MultiApplication : Application() {
    lateinit var engine: ShahbounCloneEngine
        private set

    @Volatile
    var runtimeBridgeError: Throwable? = null
        private set

    override fun onCreate() {
        super.onCreate()
        engine = ShahbounCloneEngine()
        engine.initialize(this).getOrThrow()
        runtimeBridgeError = RuntimeInstrumentationInstaller.install().exceptionOrNull()
    }

    fun requireRuntimeBridge() {
        val retry = RuntimeInstrumentationInstaller.install()
        if (retry.isSuccess) {
            runtimeBridgeError = null
            return
        }
        runtimeBridgeError = retry.exceptionOrNull()
        throw IllegalStateException("تعذر تهيئة طبقة تشغيل Shahboun على هذا الجهاز", runtimeBridgeError)
    }
}
