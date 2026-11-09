package org.fossify.home.activities

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.view.Menu
import android.view.MenuItem
import android.view.SurfaceControlViewHost
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Hosts a SurfaceView and asks EmbedService (another process) to render into it via SurfaceControlViewHost.
 * No touch listener on purpose: the system routes touches to the embedded content by itself.
 */
@Suppress("DEPRECATION")
class EmbedLabActivity : Activity(), SurfaceHolder.Callback {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var surfaceView: SurfaceView
    private lateinit var status: TextView
    private val log = StringBuilder()
    private var service: Messenger? = null
    private var surfaceReady = false
    private var w = 0
    private var h = 0
    private var sent = false
    private var gotPackage = false
    private var attachedOk = false
    private var ticks = 0
    private var clicks = 0

    private val replyHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                EmbedProto.MSG_PACKAGE -> {
                    gotPackage = true
                    try {
                        val pkg = msg.data.getParcelable<SurfaceControlViewHost.SurfacePackage>("pkg")
                        if (pkg == null) {
                            say("package was null")
                        } else {
                            surfaceView.setChildSurfacePackage(pkg)
                            attachedOk = true
                            say("setChildSurfacePackage: ok")
                        }
                    } catch (t: Throwable) {
                        say("setChildSurfacePackage failed: " + t.javaClass.name + ": " + t.message)
                    }
                }
                EmbedProto.MSG_STATS -> {
                    ticks = msg.data.getInt("ticks")
                    clicks = msg.data.getInt("clicks")
                }
                EmbedProto.MSG_ERROR -> say("embed process error: " + msg.data.getString("err"))
            }
        }
    }
    private val replyMessenger = Messenger(replyHandler)

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = Messenger(binder)
            say("service connected")
            tryEmbed()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            say("service disconnected (embed process died?)")
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            tryEmbed()
            render()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Embed lab"
        val density = resources.displayMetrics.density
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.fitsSystemWindows = true

        surfaceView = SurfaceView(this)
        surfaceView.setZOrderOnTop(true)
        surfaceView.holder.addCallback(this)
        root.addView(surfaceView, LinearLayout.LayoutParams((300 * density).toInt(), (300 * density).toInt()))

        status = TextView(this)
        status.textSize = 11f
        status.setPadding(24, 12, 24, 12)
        val scroll = ScrollView(this)
        scroll.addView(status)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        say("Android SDK " + Build.VERSION.SDK_INT + " (" + Build.MANUFACTURER + " " + Build.MODEL + ")")
        if (Build.VERSION.SDK_INT < 30) say("SurfaceControlViewHost needs Android 11+")
        bindService(Intent(this, EmbedService::class.java), conn, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unbindService(conn)
        } catch (ignored: Throwable) {
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Copy report").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, 2, 1, "Restart").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("embed", status.text))
                Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show()
            }
            2 -> recreate()
        }
        return true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        w = width
        h = height
        tryEmbed()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
    }

    private fun say(line: String) {
        log.append(line).append('\n')
    }

    private fun tryEmbed() {
        if (sent || Build.VERSION.SDK_INT < 30) return
        val s = service ?: return
        if (!surfaceReady || w == 0 || h == 0) return
        val token = surfaceView.hostToken ?: return
        val b = Bundle()
        b.putBinder("token", token)
        b.putInt("w", w)
        b.putInt("h", h)
        b.putInt("display", windowManager.defaultDisplay.displayId)
        val m = Message.obtain(null, EmbedProto.MSG_EMBED)
        m.data = b
        m.replyTo = replyMessenger
        try {
            s.send(m)
            sent = true
            say("sent embed request " + w + "x" + h)
        } catch (t: Throwable) {
            say("send failed: " + t.javaClass.name + ": " + t.message)
        }
    }

    private fun render() {
        val token = if (surfaceReady) (if (surfaceView.hostToken != null) "yes" else "NO (null)") else "surface not ready"
        val embed = when {
            ticks > 0 -> "EMBED: PASS - another process is rendering into our SurfaceView (ticks=" + ticks + ")"
            gotPackage -> "EMBED: package received, waiting for frames..."
            else -> "EMBED: waiting..."
        }
        val touch = if (clicks > 0) "TOUCH: PASS - taps reach the embedded content (clicks=" + clicks + ")" else "TOUCH: tap the button inside the window"
        status.text = log.toString() + "\nhost token available: " + token + "\nrequest sent: " + sent +
            "\npackage attached: " + attachedOk + "\n\n" + embed + "\n" + touch
    }
}
