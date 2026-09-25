package com.apk.claw.android

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Debug-only native UI fixture: assertions observe real gestures and text actions. */
class MirrorAcceptanceActivity : Activity() {
    lateinit var entry: EditText
    lateinit var tap: Button
    lateinit var scroll: ScrollView
    var taps = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(TextView(this).apply { text = "Echo 手机协同验收"; textSize = 24f })
        tap = Button(this).apply { text = "点击计数：0"; setOnClickListener { taps++; text = "点击计数：$taps" } }
        entry = EditText(this).apply { hint = "来自电脑的文字"; isSingleLine = true; showSoftInputOnFocus = false }
        layout.addView(tap); layout.addView(entry)
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        repeat(100) { index -> rows.addView(TextView(this).apply { text = "真实滚动内容 $index"; textSize = 22f; setPadding(16, 24, 16, 24) }) }
        scroll = ScrollView(this).apply { addView(rows) }
        layout.addView(scroll)
        setContentView(layout)
    }
}
