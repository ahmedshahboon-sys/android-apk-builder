package com.shahboun.multi

data class InstalledApp(val packageName:String,val label:String)
data class CloneProfile(
    val id:Long,
    val packageName:String,
    val sourceLabel:String,
    val customName:String,
    val slot:Int,
    val frozen:Boolean=false,
    val hidden:Boolean=false,
    val favorite:Boolean=false,
    val folder:String="",
    val createdAt:Long=System.currentTimeMillis(),
    val customIconPath:String=""
)
