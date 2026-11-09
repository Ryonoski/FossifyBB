package org.fossify.home.activities

import android.content.Context
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** The small test UI that gets embedded (shared by the service and the in-process fallback). */
object EmbedContent {
    class Content(val root: LinearLayout, val tickText: TextView)

    fun build(base: Context, label: String, onClick: (Int) -> Unit): Content {
        val ctx = ContextThemeWrapper(base, android.R.style.Theme_DeviceDefault)
        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setBackgroundColor(Color.parseColor("#203040"))

        val info = TextView(ctx)
        info.setTextColor(Color.WHITE)
        info.textSize = 15f
        info.gravity = Gravity.CENTER
        info.text = "EMBEDDED CONTENT\n" + label

        val tick = TextView(ctx)
        tick.setTextColor(Color.parseColor("#80FFA0"))
        tick.textSize = 16f
        tick.gravity = Gravity.CENTER

        var clicks = 0
        val btn = Button(ctx)
        btn.text = "Tap me (0)"
        btn.setOnClickListener {
            clicks++
            btn.text = "Tap me ($clicks)"
            onClick(clicks)
        }

        root.addView(info)
        root.addView(tick)
        root.addView(btn)
        return Content(root, tick)
    }
}
