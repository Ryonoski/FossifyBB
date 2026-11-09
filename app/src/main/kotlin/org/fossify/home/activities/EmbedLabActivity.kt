package org.fossify.home.activities

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
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
import java.io.File

/**
 * Embed lab v2. Tries cross-process embedding (EmbedService in ":embed"); if nothing arrives within 5 s,
 * runs the same SurfaceControlViewHost embedding in this process. Prints diagnostics either way.
 */
@Suppress("DEPRECATION")
class EmbedLabActivity : Activity(), SurfaceHolder.Callback {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var surfaceView: SurfaceView
    private lateinit var status: TextView
    private val log = StringBuilder()
    private var service: Messenger? = null
    private var bound = false
    private var surfaceReady = false
    private var w = 0
    private var h = 0
    private var sent = false
    private var gotPackage = false
    private var attachedOk = false
    private var ticks = 0
    private var clicks = 0
    private var inProcess = false
    private var inHost: SurfaceControlViewHost? = null
    private var inTick: TextView? = null
    private var inTicks = 0
    private var inClicks = 0
    private var notedProcs = false
    private val startTime = System.currentTimeMillis()

    private val replyHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                EmbedProto.MSG_PACKAGE -> {
                    gotPackage = true
                    if (inProcess) {
                        say("cross-process package arrived late; ignored")
                        return
                    }
                    try {
                        val pkg = msg.data.getParcelable<SurfaceControlViewHost.SurfacePackage>("pkg")
                        if (pkg == null) {
                            say("package was null")
                        } else {
                            surfaceView.setChildSurfacePackage(pkg)
                            attachedOk = true
                            say("cross-process: setChildSurfacePackage ok")
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

    private val inTicker = object : Runnable {
        override fun run() {
            inTicks++
            inTick?.text = "embedded ticks: $inTicks"
            handler.postDelayed(this, 1000)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            tryEmbed()
            val age = System.currentTimeMillis() - startTime
            if (!notedProcs && age > 2500) {
                notedProcs = true
                noteProcesses()
            }
            if (!gotPackage && !inProcess && age > 5000 && surfaceReady && w > 0) startInProcess()
            render()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Embed lab"
        // park any old crash log so a new one means "crashed during this test"
        try {
            val old = File(filesDir, "crash.txt")
            if (old.exists()) old.renameTo(File(filesDir, "crash.old.txt"))
        } catch (ignored: Throwable) {
        }
        val density = resources.displayMetrics.density
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.fitsSystemWindows = true

        surfaceView = SurfaceView(this)
        surfaceView.setZOrderOnTop(true)
        surfaceView.holder.addCallback(this)
        root.addView(surfaceView, LinearLayout.LayoutParams((300 * density).toInt(), (260 * density).toInt()))

        status = TextView(this)
        status.textSize = 11f
        status.setPadding(24, 12, 24, 12)
        val scroll = ScrollView(this)
        scroll.addView(status)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        say("Android SDK " + Build.VERSION.SDK_INT + " (" + Build.MANUFACTURER + " " + Build.MODEL + ")")
        say("this process: " + Application.getProcessName())
        if (Build.VERSION.SDK_INT < 30) say("SurfaceControlViewHost needs Android 11+")
        try {
            bound = bindService(Intent(this, EmbedService::class.java), conn, Context.BIND_AUTO_CREATE)
        } catch (t: Throwable) {
            say("bindService threw: " + t.javaClass.name + ": " + t.message)
        }
        say("bindService returned " + bound)
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
        handler.removeCallbacks(inTicker)
        try {
            inHost?.release()
        } catch (ignored: Throwable) {
        }
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

    private fun noteProcesses() {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val names = am.runningAppProcesses?.map { it.processName } ?: emptyList()
            say("app processes now: " + names.joinToString(", "))
        } catch (t: Throwable) {
            say("could not list processes: " + t.javaClass.simpleName)
        }
        say("service connected: " + (service != null))
        try {
            val f = File(filesDir, "crash.txt")
            if (f.exists()) {
                say("crash during this test (" + ((System.currentTimeMillis() - f.lastModified()) / 1000) + "s ago):\n" + f.readText().take(1800))
            } else {
                say("no crash recorded during this test")
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun tryEmbed() {
        if (sent || inProcess || Build.VERSION.SDK_INT < 30) return
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

    private fun startInProcess() {
        inProcess = true
        say("cross-process did not deliver in 5 s -> running the SAME embedding in this process")
        try {
            val host = SurfaceControlViewHost(this, windowManager.defaultDisplay, surfaceView.hostToken)
            inHost = host
            val c = EmbedContent.build(this, "in-process: " + Application.getProcessName()) { n -> inClicks = n }
            inTick = c.tickText
            host.setView(c.root, w, h)
            val pkg = host.surfacePackage
            if (pkg == null) {
                say("in-process: surfacePackage was null")
                return
            }
            surfaceView.setChildSurfacePackage(pkg)
            say("in-process: attached")
            handler.post(inTicker)
        } catch (t: Throwable) {
            say("in-process failed: " + t.javaClass.name + ": " + t.message)
        }
    }

    private fun render() {
        val age = System.currentTimeMillis() - startTime
        val token = if (surfaceReady) (if (surfaceView.hostToken != null) "yes" else "NO (null)") else "surface not ready"
        val cross = when {
            ticks > 0 -> "CROSS-PROCESS: PASS - another process renders into our SurfaceView (ticks=" + ticks + ")"
            gotPackage -> "CROSS-PROCESS: package received, no frames yet"
            service == null && age > 4000 -> "CROSS-PROCESS: FAIL - service never connected (bindService returned " + bound + ")"
            else -> "CROSS-PROCESS: waiting..."
        }
        val inproc = if (inProcess) {
            "IN-PROCESS: " + (if (inTicks > 0) "PASS - renders (ticks=" + inTicks + ")" else "attached, no frames yet")
        } else "IN-PROCESS: not needed / not run"
        val touchCount = clicks + inClicks
        val touch = if (touchCount > 0) "TOUCH: PASS - taps reach the embedded content (clicks=" + touchCount + ")" else "TOUCH: tap the button inside the window"
        status.text = log.toString() + "\nhost token available: " + token + "\nrequest sent: " + sent +
            "\n\n" + cross + "\n" + inproc + "\n" + touch
    }
}
