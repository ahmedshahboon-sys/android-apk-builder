package com.shahboun.multi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class CloneStore(context: Context) {
    private val p=context.getSharedPreferences("clone_store",Context.MODE_PRIVATE)
    fun list():MutableList<CloneProfile>{
        val arr=JSONArray(p.getString("items","[]")); val out=mutableListOf<CloneProfile>()
        for(i in 0 until arr.length()){
            val o=arr.getJSONObject(i)
            out += CloneProfile(
                o.getLong("id"),
                o.getString("pkg"),
                o.getString("label"),
                o.getString("name"),
                o.getInt("slot"),
                o.optBoolean("frozen"),
                o.optBoolean("hidden"),
                o.optBoolean("favorite"),
                o.optString("folder"),
                o.optLong("created",System.currentTimeMillis()),
                o.optString("iconPath","")
            )
        }
        return out
    }
    fun save(items:List<CloneProfile>){
        val a=JSONArray()
        items.forEach{ c->
            a.put(JSONObject().apply{
                put("id",c.id);put("pkg",c.packageName);put("label",c.sourceLabel);put("name",c.customName);put("slot",c.slot)
                put("frozen",c.frozen);put("hidden",c.hidden);put("favorite",c.favorite);put("folder",c.folder);put("created",c.createdAt)
                put("iconPath",c.customIconPath)
            })
        }
        p.edit().putString("items",a.toString()).apply()
    }
}
