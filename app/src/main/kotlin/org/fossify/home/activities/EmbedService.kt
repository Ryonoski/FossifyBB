package org.fossify.home.activities

import android.app.Application
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.SurfaceControlViewHost
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Runs in its own process (":embed"), like a guest app process would. It renders a small UI into a
 * SurfaceControlViewHost and hands the SurfacePackage to the launcher process, which attaches it to a SurfaceView.
 */
class EmbedService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var host: SurfaceControlViewHost? = null
    private var client: Messenger? = null
    private var ticks = 0
    private var clicks = 0
    private var tickText: TextView? = null

    private val incoming = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what == EmbedProto.MSG_EMBED) embed(msg)
        }
    })

    private val statsTick = object : Runnable {
        override fun run() {
            ticks++
            tickText?.text = "embedded ticks: $ticks"
            val out = Bundle()
            out.putInt("ticks", ticks)
            out.putInt("clicks", clicks)
            reply(EmbedProto.MSG_STATS, out)
            main.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder = incoming.binder

    override fun onDestroy() {
        main.removeCallbacks(statsTick)
        try {
            host?.release()
        } catch (ignored: Throwable) {
        }
        host = null
        super.onDestroy()
    }

    private fun reply(what: Int, data: Bundle) {
        try {
            val m = Message.obtain(null, what)
            m.data = data
            client?.send(m)
        } catch (ignored: Throwable) {
        }
    }

    private fun embed(msg: Message) {
        client = msg.replyTo
        val b = msg.data
        val w = b.getInt("w")
        val h = b.getInt("h")
        val displayId = b.getInt("display")
        val token = b.getBinder("token")
        try {
            host?.release()
            val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val display = dm.getDisplay(displayId)
            val svc = SurfaceControlViewHost(ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault), display, token)
            host = svc
            svc.setView(buildContent(), w, h)
            val out = Bundle()
            out.putParcelable("pkg", svc.surfacePackage)
            reply(EmbedProto.MSG_PACKAGE, out)
            main.removeCallbacks(statsTick)
            main.post(statsTick)
        } catch (t: Throwable) {
            val out = Bundle()
            out.putString("err", t.javaClass.name + ": " + t.message + "\n" + Log.getStackTraceString(t).take(1500))
            reply(EmbedProto.MSG_ERROR, out)
        }
    }

    private fun buildContent(): LinearLayout {
        val ctx = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault)
        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setBackgroundColor(Color.parseColor("#203040"))

        val info = TextView(ctx)
        info.setTextColor(Color.WHITE)
        info.textSize = 16f
        info.gravity = Gravity.CENTER
        info.text = "EMBEDDED CONTENT\nprocess: " + Application.getProcessName()

        val tick = TextView(ctx)
        tick.setTextColor(Color.parseColor("#80FFA0"))
        tick.textSize = 16f
        tick.gravity = Gravity.CENTER
        tickText = tick

        val btn = Button(ctx)
        btn.text = "Tap me (0)"
        btn.setOnClickListener {
            clicks++
            btn.text = "Tap me ($clicks)"
        }

        root.addView(info)
        root.addView(tick)
        root.addView(btn)
        return root
    }
}
