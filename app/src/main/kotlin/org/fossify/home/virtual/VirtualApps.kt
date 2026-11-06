package org.fossify.home.virtual

import android.app.Activity
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import org.fossify.home.models.AppLauncher
import top.niunaijun.blackbox.BlackBoxCore

/**
 * Virtual apps appear to the launcher as normal apps with a synthetic package name:
 *   bbv:<userId>:<realPackageName>
 */
object VirtualApps {
    private const val TAG = "VirtualApps"
    private const val PREFIX = "bbv:"
    const val ACTIVITY_NAME = "virtual"
    private val main = Handler(Looper.getMainLooper())

    fun isVirtual(pkg: String?): Boolean = pkg != null && pkg.startsWith(PREFIX)

    private fun encode(user: Int, pkg: String): String = PREFIX + user + ":" + pkg
    private fun userOf(id: String): Int = id.removePrefix(PREFIX).substringBefore(':').toIntOrNull() ?: 0
    private fun realPackage(id: String): String = id.removePrefix(PREFIX).substringAfter(':')

    @Suppress("UNUSED_PARAMETER")
    fun launchers(ctx: Context): List<AppLauncher> {
        val out = ArrayList<AppLauncher>()
        try {
            val core = BlackBoxCore.get()
            if (!core.waitForServicesAvailable(2000)) return out
            val pm = BlackBoxCore.getPackageManager()
            for (u in core.users) {
                for (ai in core.getInstalledApplications(0, u.id)) {
                    val suffix = if (u.id == 0) " (V)" else " (V" + u.id + ")"
                    out.add(
                        AppLauncher(
                            null,
                            ai.loadLabel(pm).toString() + suffix,
                            encode(u.id, ai.packageName),
                            ACTIVITY_NAME,
                            0,
                            0,
                            ai.loadIcon(pm)
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "listing virtual apps failed", t)
        }
        return out
    }

    fun icon(id: String): Drawable? {
        return try {
            val real = realPackage(id)
            BlackBoxCore.get().getInstalledApplications(0, userOf(id))
                .firstOrNull { it.packageName == real }
                ?.loadIcon(BlackBoxCore.getPackageManager())
        } catch (t: Throwable) {
            null
        }
    }

    fun launch(ctx: Context, id: String) {
        Thread {
            try {
                BlackBoxCore.get().launchApk(realPackage(id), userOf(id))
            } catch (t: Throwable) {
                Log.e(TAG, "launch failed", t)
                toast(ctx, "Launch failed: " + t.javaClass.simpleName + ": " + t.message)
            }
        }.start()
    }

    fun remove(ctx: Context, id: String) {
        Thread {
            try {
                BlackBoxCore.get().uninstallPackageAsUser(realPackage(id), userOf(id))
            } catch (t: Throwable) {
                Log.e(TAG, "remove failed", t)
            }
            main.post { (ctx as? Activity)?.recreate() }
        }.start()
    }

    fun toast(ctx: Context, msg: String) {
        main.post { Toast.makeText(ctx.applicationContext, msg, Toast.LENGTH_LONG).show() }
    }
}
