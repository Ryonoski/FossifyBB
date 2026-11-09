package org.fossify.home.activities

import android.app.Application
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import android.view.SurfaceControlViewHost
import android.widget.TextView

/**
 * Runs in its own process (":embed"), like a guest app process would. Renders UI into a SurfaceControlViewHost
 * and hands the SurfacePackage to the launcher process.
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
            val svc = SurfaceControlViewHost(this, display, token)
            host = svc
            val c = EmbedContent.build(this, "cross-process: " + Application.getProcessName()) { n -> clicks = n }
            tickText = c.tickText
            svc.setView(c.root, w, h)
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
}
