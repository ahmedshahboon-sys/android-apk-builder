package com.shahboun.multi

import android.app.Application

class MultiApplication:Application(){
    lateinit var engine:CloneEngine
    override fun onCreate(){ super.onCreate(); engine=ReflectiveBlackBoxEngine().takeIf{it.isAvailable()}?:SafeFallbackEngine(this); engine.initialize(this) }
}
