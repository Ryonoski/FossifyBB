package org.fossify.home.activities

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import top.niunaijun.blackbox.BlackBoxCore
import java.io.File

class VirtualAppsActivity : Activity() {

    private data class VApp(val pkg: String, val label: String, val user: Int) {
        override fun toString() = if (user == 0) label else "$label  (user $user)"
    }

    private val apps = ArrayList<VApp>()
    private lateinit var adapter: ArrayAdapter<VApp>
    private lateinit var emptyText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Virtual Apps"
        val list = ListView(this)
        emptyText = TextView(this).apply {
            text = "Loading..."
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setTextIsSelectable(true)
        }
        val match = FrameLayout.LayoutParams.MATCH_PARENT
        val root = FrameLayout(this)
        root.fitsSystemWindows = true
        root.addView(list, FrameLayout.LayoutParams(match, match))
        root.addView(emptyText, FrameLayout.LayoutParams(match, match))
        list.emptyView = emptyText
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, apps)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> launch(apps[position]) }
        list.setOnItemLongClickListener { _, _, position, _ -> confirmRemove(apps[position]); true }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_ADD, 0, "Add app").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, MENU_LOG, 1, "Copy last crash").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_XP, 2, "Xposed modules").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, MENU_LAB, 3, "Display lab").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_ADD -> showAddDialog()
            MENU_LOG -> copyCrash()
            MENU_XP -> startActivity(Intent(this, XposedActivity::class.java))
            MENU_LAB -> startActivity(Intent(this, DisplayLabActivity::class.java))
        }
        return true
    }

    private fun refresh() {
        Thread {
            val result = ArrayList<VApp>()
            var error: String? = null
            try {
                val core = BlackBoxCore.get()
                core.waitForServicesAvailable(15000)
                val pm = BlackBoxCore.getPackageManager()
                for (u in core.users) {
                    for (ai in core.getInstalledApplications(0, u.id)) {
                        result.add(VApp(ai.packageName, ai.loadLabel(pm).toString(), u.id))
                    }
                }
            } catch (t: Throwable) {
                error = "Engine error: " + t.javaClass.simpleName + ": " + t.message
            }
            result.sortBy { it.label.lowercase() }
            runOnUiThread {
                apps.clear()
                apps.addAll(result)
                adapter.notifyDataSetChanged()
                emptyText.text = error ?: "No virtual apps yet. Tap 'Add app' to clone one."
            }
        }.start()
    }

    private fun showAddDialog() {
        Thread {
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val choices = packageManager.queryIntentActivities(main, 0)
                .map { Pair(it.loadLabel(packageManager).toString(), it.activityInfo.packageName) }
                .filter { it.second != applicationContext.packageName }
                .distinctBy { it.second }
                .sortedBy { it.first.lowercase() }
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("Add app to virtual space")
                    .setItems(choices.map { it.first }.toTypedArray()) { _, which -> install(choices[which].second) }
                    .show()
            }
        }.start()
    }

    private fun install(pkg: String) {
        Toast.makeText(this, "Adding...", Toast.LENGTH_SHORT).show()
        Thread {
            var msg = "Added"
            try {
                BlackBoxCore.get().installPackageAsUser(pkg, 0)
            } catch (t: Throwable) {
                msg = "Add failed: " + t.javaClass.simpleName + ": " + t.message
            }
            runOnUiThread {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                refresh()
            }
        }.start()
    }

    private fun confirmRemove(app: VApp) {
        AlertDialog.Builder(this)
            .setTitle("Remove " + app.label + "?")
            .setMessage("Deletes the virtual copy and its data.")
            .setPositiveButton("Remove") { _, _ ->
                Thread {
                    try {
                        BlackBoxCore.get().uninstallPackageAsUser(app.pkg, app.user)
                    } catch (ignored: Throwable) {
                    }
                    runOnUiThread { refresh() }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun launch(app: VApp) {
        Thread {
            var err: String? = null
            try {
                BlackBoxCore.get().launchApk(app.pkg, app.user)
            } catch (t: Throwable) {
                err = "Launch failed: " + t.javaClass.simpleName + ": " + t.message
            }
            if (err != null) {
                runOnUiThread { Toast.makeText(this, err, Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun copyCrash() {
        val f = File(filesDir, "crash.txt")
        if (!f.exists()) {
            Toast.makeText(this, "No crash logged", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("crash", f.readText()))
        Toast.makeText(this, "Crash log copied", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val MENU_ADD = 1
        private const val MENU_LOG = 2
        private const val MENU_XP = 3
        private const val MENU_LAB = 4
    }
}
