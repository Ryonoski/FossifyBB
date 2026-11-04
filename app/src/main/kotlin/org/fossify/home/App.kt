package org.fossify.home

import android.content.Context
import android.util.Log
import org.fossify.commons.FossifyApp
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.app.configuration.ClientConfiguration
import java.io.File

class App : FossifyApp() {

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        if (base == null) return
        try { BlackBoxCore.get().closeCodeInit() } catch (t: Throwable) { Log.e(TAG, "closeCodeInit", t) }
        try { BlackBoxCore.get().onBeforeMainApplicationAttach(this, base) } catch (t: Throwable) { Log.e(TAG, "before attach", t) }
        try {
            BlackBoxCore.get().doAttachBaseContext(base, object : ClientConfiguration() {
                override fun getHostPackageName(): String = base.packageName
                override fun isEnableDaemonService(): Boolean = false
            })
        } catch (t: Throwable) {
            Log.e(TAG, "doAttachBaseContext", t)
        }
        try { BlackBoxCore.get().onAfterMainApplicationAttach(this, base) } catch (t: Throwable) { Log.e(TAG, "after attach", t) }
        installCrashLogger()
    }

    override fun onCreate() {
        super.onCreate()
        try { BlackBoxCore.get().doCreate() } catch (t: Throwable) { Log.e(TAG, "doCreate", t) }
        installCrashLogger()
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashLogger) return
        Thread.setDefaultUncaughtExceptionHandler(CrashLogger(this, previous))
    }

    companion object {
        private const val TAG = "LauncherApp"
    }
}

class CrashLogger(
    private val ctx: Context,
    private val previous: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            File(ctx.filesDir, "crash.txt").writeText(
                "pid=" + android.os.Process.myPid() + " thread=" + t.name + "\n" + Log.getStackTraceString(e)
            )
        } catch (ignored: Throwable) {
        }
        previous?.uncaughtException(t, e)
    }
}
