package com.shahboun.multi

import android.content.Context

interface CloneEngine {
    val name:String
    fun isAvailable():Boolean
    fun initialize(context:Context):Result<Unit>
    fun createClone(packageName:String,slot:Int):Result<Unit>
    fun launch(packageName:String,slot:Int):Result<Unit>
    fun remove(packageName:String,slot:Int):Result<Unit>
    fun clearData(packageName:String,slot:Int):Result<Unit>
}

class ReflectiveBlackBoxEngine:CloneEngine{
    override val name="BlackBox / compatible virtual engine"
    private var core:Any?=null
    override fun isAvailable():Boolean=runCatching{Class.forName("top.niunaijun.blackbox.BlackBoxCore")}.isSuccess
    override fun initialize(context:Context):Result<Unit> = runCatching<Unit>{
        val c=Class.forName("top.niunaijun.blackbox.BlackBoxCore")
        core=c.getMethod("get").invoke(null)
        Unit
    }
    private fun invokeBest(method:String,vararg args:Any?):Any?{
        val obj=core?:error("Engine not initialized")
        val m=obj.javaClass.methods.firstOrNull{it.name==method && it.parameterCount==args.size}?:error("Engine API method missing: $method")
        return m.invoke(obj,*args)
    }
    override fun createClone(packageName:String,slot:Int):Result<Unit> = runCatching<Unit>{ invokeBest("installPackageAsUser",packageName,slot); Unit }
    override fun launch(packageName:String,slot:Int):Result<Unit> = runCatching<Unit>{
        val obj=core?:error("Engine not initialized")
        val names=listOf("launchApk","launchApp","launchPackage")
        val m=obj.javaClass.methods.firstOrNull{it.name in names && it.parameterCount==2}?:error("Launch API not exposed by installed engine")
        m.invoke(obj,packageName,slot)
        Unit
    }
    override fun remove(packageName:String,slot:Int):Result<Unit> = runCatching<Unit>{ invokeBest("uninstallPackage",packageName,slot); Unit }
    override fun clearData(packageName:String,slot:Int):Result<Unit> = runCatching<Unit>{ invokeBest("clearAppData",packageName,slot); Unit }
}

class SafeFallbackEngine(private val context:Context):CloneEngine{
    override val name="Android launcher fallback"
    override fun isAvailable():Boolean=true
    override fun initialize(context:Context):Result<Unit> = Result.success(Unit)
    override fun createClone(packageName:String,slot:Int):Result<Unit> = Result.failure(UnsupportedOperationException("A virtualization engine is required for isolated multi-instance clones."))
    override fun launch(packageName:String,slot:Int):Result<Unit> = runCatching<Unit>{
        val i=context.packageManager.getLaunchIntentForPackage(packageName)?:error("No launch activity")
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
        Unit
    }
    override fun remove(packageName:String,slot:Int):Result<Unit> = Result.failure(UnsupportedOperationException("No virtual engine"))
    override fun clearData(packageName:String,slot:Int):Result<Unit> = Result.failure(UnsupportedOperationException("No virtual engine"))
}
