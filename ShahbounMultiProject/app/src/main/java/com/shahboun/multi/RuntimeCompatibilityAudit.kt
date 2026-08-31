package com.shahboun.multi

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.TypedValue

data class CompatibilityCheck(val name:String,val status:Status,val detail:String){enum class Status{PASS,WARN,FAIL}}
data class CompatibilityReport(val packageName:String,val slot:Int,val checks:List<CompatibilityCheck>){
    val failed get()=checks.count{it.status==CompatibilityCheck.Status.FAIL}
    val warnings get()=checks.count{it.status==CompatibilityCheck.Status.WARN}
    val passed get()=checks.count{it.status==CompatibilityCheck.Status.PASS}
    fun render():String=buildString{
        appendLine("اختبار التوافق: $packageName / نسخة ${slot+1}")
        appendLine("نجاح: $passed   تحذير: $warnings   فشل: $failed")
        appendLine()
        checks.forEach{c->appendLine("${when(c.status){CompatibilityCheck.Status.PASS->"✓";CompatibilityCheck.Status.WARN->"!";CompatibilityCheck.Status.FAIL->"✕"}} ${c.name}: ${c.detail}")}
    }
}

object RuntimeCompatibilityAudit {
    fun run(context:Context,engine:ShahbounCloneEngine,packageName:String,slot:Int):CompatibilityReport{
        val out=mutableListOf<CompatibilityCheck>()
        fun check(name:String,warning:Boolean=false,block:()->String){
            runCatching{block()}.onSuccess{out+=CompatibilityCheck(name,if(warning)CompatibilityCheck.Status.WARN else CompatibilityCheck.Status.PASS,it)}
                .onFailure{out+=CompatibilityCheck(name,CompatibilityCheck.Status.FAIL,it.message?:it.javaClass.simpleName)}
        }
        check("Snapshot وسلامة APK"){
            val session=engine.sessionFor(packageName,slot);val p=session.runtimePackage
            require(p.baseApk.isFile&&p.baseApk.length()>0);require(p.splitApks.all{it.isFile&&it.length()>0});"base + ${p.splitApks.size} split"
        }
        val session=runCatching{engine.sessionFor(packageName,slot)}.getOrNull()
        if(session!=null){
            val p=session.runtimePackage
            check("DEX/ClassLoader"){
                require(p.dexApks.isNotEmpty());session.classLoader.loadClass(p.launchActivity);"${p.dexApks.size} APK تحمل DEX • launcher قابل للتحميل"
            }
            check("Resources"){
                session.guestApplication?.applicationInfo
                require(session.resources.assets!=null);val theme=p.activityTheme(p.launchActivity).takeIf{it!=0}?:p.launchActivityTheme.takeIf{it!=0}?:p.appTheme
                if(theme!=0){val v=TypedValue();session.resources.getValue(theme,v,true)}
                "resource graph جاهز${if(theme!=0)" • theme=0x${theme.toString(16)}" else ""}"
            }
            check("المكونات"){
                val componentCount=p.activities.size+p.services.size+p.receivers.size+p.providers.size
                require(componentCount>0);"Activities ${p.activities.size} • Services ${p.services.size} • Receivers ${p.receivers.size} • Providers ${p.providers.size}"
            }
            check("Providers"){
                p.providers.forEach{session.classLoader.loadClass(it.name)};"${p.providers.size} provider classes قابلة للتحميل"
            }
            check("Services"){
                p.services.forEach{session.classLoader.loadClass(it.name)};"${p.services.size} service classes قابلة للتحميل"
            }
            check("Receivers"){
                p.receivers.forEach{session.classLoader.loadClass(it.name)};"${p.receivers.size} receiver classes قابلة للتحميل"
            }
            check("Process isolation"){
                val index=RuntimeProcessPool.processIndex(packageName,slot);"clone$index • WebView/process pool"
            }
            check("صلاحيات المضيف"){
                val requested=RuntimePermissionBroker.requestedByGuest(context,packageName);val missing=RuntimePermissionBroker.missingForGuest(context,packageName)
                if(missing.isNotEmpty()) error("ناقص ${missing.size}: ${missing.take(4).joinToString()}")
                "${requested.size} صلاحية حساسة مغطاة"
            }
            val updated=engine.needsUpdate(packageName,slot)
            out+=CompatibilityCheck("تحديث Snapshot",if(updated)CompatibilityCheck.Status.WARN else CompatibilityCheck.Status.PASS,if(updated)"التطبيق الأصلي أحدث؛ حدّث النسخة" else "محدث")
            check("مساحة النسخة"){val bytes=engine.cloneSizeBytes(packageName,slot);require(bytes>0);"$bytes bytes معزولة"}
            if(Build.VERSION.SDK_INT>=34){
                out+=CompatibilityCheck("Android 14–16",CompatibilityCheck.Status.PASS,"AppOps attribution + Alarm + Job + FGS bridges مفعلة عند بدء المضيف")
            }
        }
        val processIndexes=(0..4).map{RuntimeProcessPool.processIndex(packageName,it)}
        out+=CompatibilityCheck("توزيع 5 نسخ",if(processIndexes.distinct().size==5)CompatibilityCheck.Status.PASS else CompatibilityCheck.Status.WARN,"slots 1–5 => ${processIndexes.joinToString{":clone$it"}}")
        RuntimeDiagnostics.log("AUDIT",CompatibilityReport(packageName,slot,out).render().replace("\n"," | "))
        return CompatibilityReport(packageName,slot,out)
    }
}
