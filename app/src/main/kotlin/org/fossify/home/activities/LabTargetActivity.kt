package org.fossify.home.activities

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** A plain activity we try to run on a virtual display. */
class LabTargetActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var tickText: TextView
    private var clicks = 0

    private val tick = object : Runnable {
        override fun run() {
            LabState.ticks++
            tickText.text = "ticks: " + LabState.ticks
            handler.postDelayed(this, 1000)
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setBackgroundColor(Color.parseColor("#203040"))

        val info = TextView(this)
        info.setTextColor(Color.WHITE)
        info.textSize = 18f
        info.text = "LAB TARGET\nrunning on display " + windowManager.defaultDisplay.displayId
        info.gravity = Gravity.CENTER

        tickText = TextView(this)
        tickText.setTextColor(Color.parseColor("#80FFA0"))
        tickText.textSize = 16f
        tickText.gravity = Gravity.CENTER

        val btn = Button(this)
        btn.text = "Tap me (0)"
        btn.setOnClickListener {
            clicks++
            LabState.clicks = clicks
            btn.text = "Tap me ($clicks)"
        }

        root.addView(info)
        root.addView(tickText)
        root.addView(btn)
        setContentView(root)
    }

    @Suppress("DEPRECATION")
    override fun onResume() {
        super.onResume()
        LabState.targetActivity = this
        LabState.targetDisplayId = windowManager.defaultDisplay.displayId
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (LabState.targetActivity === this) LabState.targetActivity = null
    }
}
