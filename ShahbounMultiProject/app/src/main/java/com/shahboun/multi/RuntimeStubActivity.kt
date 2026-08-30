package com.shahboun.multi

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * Declared host Activity used only as an Android task/token target.
 * Under a healthy ShahbounInstrumentation bridge this class is replaced by the
 * guest launch Activity before onCreate. If it appears, the bridge failed safely.
 */
class RuntimeStubActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "تعذر تشغيل النسخة: طبقة Shahboun Runtime لم تستبدل شاشة التشغيل"
            textSize = 18f
            setPadding(40, 80, 40, 40)
        })
    }
}
