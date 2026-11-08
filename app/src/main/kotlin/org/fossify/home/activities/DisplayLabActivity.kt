package org.fossify.home.activities

import android.app.Activity
import android.app.ActivityOptions
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Feasibility probe v2: create a virtual display rendering into a SurfaceView, then try several ways
 * of starting an activity on it (with full stack traces), forward touches, and print a report.
 */
class DisplayLabActivity : Activity(), SurfaceHolder.Callback {

    private class Strategy(val name: String, val run: (Intent, Int, Display) -> Unit)

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var surfaceView: SurfaceView
    private lateinit var status: TextView
    private val log = StringBuilder()
    private var vd: VirtualDisplay? = null
    private var vw = 0
    private var vh = 0
    private var forwarded = 0
    private var verdict = ""
    private var gen = 0

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Display lab"
        val density = resources.displayMetrics.density
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.fitsSystemWindows = true

        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        surfaceView.setOnTouchListener { v, ev -> forwardTouch(v, ev); true }
        root.addView(surfaceView, LinearLayout.LayoutParams((300 * density).toInt(), (300 * density).toInt()))

        status = TextView(this)
        status.textSize = 11f
        status.setPadding(24, 12, 24, 12)
        val scroll = ScrollView(this)
        scroll.addView(status)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
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
        releaseLab()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Copy report").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, 2, 1, "Restart test").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("lab", status.text))
                Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show()
            }
            2 -> {
                releaseLab()
                log.setLength(0)
                verdict = ""
                forwarded = 0
                val h = surfaceView.holder
                if (h.surface != null && h.surface.isValid) startLab(h, vw, vh)
            }
        }
        return true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        vw = width
        vh = height
        if (vd == null) startLab(holder, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        releaseLab()
    }

    private fun say(line: String) {
        log.append(line).append('\n')
    }

    private fun shortTrace(t: Throwable): String {
        val sb = StringBuilder(t.javaClass.name + ": " + t.message)
        val frames = t.stackTrace
        for (i in 0 until minOf(frames.size, 12)) sb.append("\n      at ").append(frames[i].toString())
        var c = t.cause
        var depth = 0
        while (c != null && depth < 2) {
            sb.append("\n    caused by ").append(c.javaClass.name).append(": ").append(c.message)
            val cf = c.stackTrace
            for (i in 0 until minOf(cf.size, 8)) sb.append("\n      at ").append(cf[i].toString())
            c = c.cause
            depth++
        }
        return sb.toString()
    }

    private fun startLab(holder: SurfaceHolder, w: Int, h: Int) {
        say("Android SDK " + Build.VERSION.SDK_INT + " (" + Build.MANUFACTURER + " " + Build.MODEL + ")")
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val dpi = resources.configuration.densityDpi
        val attempts = listOf(
            Pair("public + own-content", DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY),
            Pair("private own-content", DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
        )
        for (attempt in attempts) {
            try {
                val d = dm.createVirtualDisplay("lab", w, h, dpi, holder.surface, attempt.second)
                if (d != null) {
                    vd = d
                    val disp = d.display
                    say("virtual display (" + attempt.first + "): id=" + disp.displayId + " " + w + "x" + h + " dpi " + dpi +
                        " flags=0x" + Integer.toHexString(disp.flags) + " valid=" + disp.isValid)
                    break
                }
                say("createVirtualDisplay returned null (" + attempt.first + ")")
            } catch (t: Throwable) {
                say("createVirtualDisplay failed (" + attempt.first + "): " + shortTrace(t))
            }
        }
        val d = vd
        if (d == null) {
            verdict = "VERDICT: FAIL - could not create a virtual display"
            return
        }
        launchTarget(d.display.displayId, d.display)
    }

    private fun options(displayId: Int): Bundle {
        val opts = ActivityOptions.makeBasic()
        opts.setLaunchDisplayId(displayId)
        return opts.toBundle()
    }

    private fun launchTarget(displayId: Int, display: Display) {
        val strategies = listOf(
            Strategy("control: no options, default display") { i, _, _ -> startActivity(i) },
            Strategy("activity.startActivity + launchDisplayId") { i, id, _ -> startActivity(i, options(id)) },
            Strategy("applicationContext.startActivity + launchDisplayId") { i, id, _ -> applicationContext.startActivity(i, options(id)) },
            Strategy("createDisplayContext(display).startActivity") { i, _, d -> createDisplayContext(d).startActivity(i) }
        )
        runStrategy(strategies, 0, displayId, display, gen)
    }

    private fun runStrategy(list: List<Strategy>, index: Int, displayId: Int, display: Display, g: Int) {
        if (g != gen) return
        if (index >= list.size) {
            verdict = "VERDICT: FAIL - no launch strategy put the activity on the virtual display"
            return
        }
        val s = list[index]
        LabState.reset()
        val intent = Intent(this, LabTargetActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        var err: String? = null
        try {
            s.run(intent, displayId, display)
        } catch (t: Throwable) {
            err = shortTrace(t)
        }
        handler.postDelayed({
            if (g != gen) return@postDelayed
            val actual = LabState.targetDisplayId
            val result = when {
                err != null -> "EXCEPTION " + err
                actual == null -> "no activity appeared"
                else -> "ran on display " + actual
            }
            say("[" + (index + 1) + "] " + s.name + ": " + result)
            if (index > 0 && err == null && actual != null && actual == displayId) {
                verdict = "VERDICT: PASS via [" + s.name + "]"
                return@postDelayed
            }
            try {
                LabState.targetActivity?.finish()
            } catch (ignored: Throwable) {
            }
            handler.postDelayed({ runStrategy(list, index + 1, displayId, display, g) }, 600)
        }, 1800)
    }

    private fun forwardTouch(v: View, ev: MotionEvent) {
        val target = LabState.targetActivity ?: return
        if (vw == 0 || vh == 0 || v.width == 0 || v.height == 0) return
        val copy = MotionEvent.obtain(ev)
        copy.setLocation(ev.x * vw / v.width, ev.y * vh / v.height)
        try {
            target.window.decorView.dispatchTouchEvent(copy)
            if (ev.actionMasked == MotionEvent.ACTION_UP) forwarded++
        } catch (t: Throwable) {
            say("touch forward failed: " + t.javaClass.simpleName + ": " + t.message)
        } finally {
            copy.recycle()
        }
    }

    private fun releaseLab() {
        gen++
        try {
            vd?.release()
        } catch (ignored: Throwable) {
        }
        vd = null
        val t = LabState.targetActivity
        if (t != null) {
            try {
                t.finish()
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun render() {
        val live = "target display: " + (LabState.targetDisplayId?.toString() ?: "-") +
            "\ntarget ticks (proves it renders): " + LabState.ticks +
            "\ntouch-ups forwarded: " + forwarded +
            "\nbutton clicks seen by target: " + LabState.clicks
        val touchVerdict = if (forwarded > 0) {
            if (LabState.clicks > 0) "TOUCH: PASS - forwarded taps reach the target" else "TOUCH: forwarded " + forwarded + " but target got no click (tap the button)"
        } else "TOUCH: tap the button inside the window above"
        status.text = log.toString() + "\n" + verdict + "\n" + touchVerdict + "\n\n--- live ---\n" + live
    }
}
