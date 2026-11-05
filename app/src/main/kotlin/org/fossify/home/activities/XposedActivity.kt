package org.fossify.home.activities

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
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

class XposedActivity : Activity() {

    private data class XModule(val pkg: String, val label: String, val added: Boolean, val enabled: Boolean) {
        override fun toString(): String {
            val state = if (!added) "not added" else if (enabled) "enabled" else "disabled"
            return label + "\n" + pkg + "  -  " + state
        }
    }

    private class Opt(val label: String, val run: () -> Unit)

    private val modules = ArrayList<XModule>()
    private lateinit var adapter: ArrayAdapter<XModule>
    private lateinit var emptyText: TextView
    private var frameworkOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Xposed modules"
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
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, modules)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> showOptions(modules[position]) }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_FRAMEWORK, 0, "Framework").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(MENU_FRAMEWORK)?.title =
            if (frameworkOn) "Framework: ON (tap to turn off)" else "Framework: OFF (tap to turn on)"
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_FRAMEWORK) {
            act("Framework switched") { BlackBoxCore.get().setXPEnable(!frameworkOn) }
        }
        return true
    }

    private fun refresh() {
        Thread {
            val found = ArrayList<XModule>()
            var on = false
            var error: String? = null
            try {
                val core = BlackBoxCore.get()
                core.waitForServicesAvailable(15000)
                on = core.isXPEnable()
                val pm = packageManager
                for (ai in pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
                    if (ai.packageName == applicationContext.packageName) continue
                    val md = ai.metaData ?: continue
                    if (!md.containsKey("xposedmodule") && !md.containsKey("xposedminversion")) continue
                    var added = false
                    var enabled = false
                    try { added = core.isInstalledXposedModule(ai.packageName) } catch (ignored: Throwable) { }
                    if (added) {
                        try { enabled = core.isModuleEnable(ai.packageName) } catch (ignored: Throwable) { }
                    }
                    found.add(XModule(ai.packageName, ai.loadLabel(pm).toString(), added, enabled))
                }
            } catch (t: Throwable) {
                error = "Engine error: " + t.javaClass.simpleName + ": " + t.message
            }
            found.sortBy { it.label.lowercase() }
            runOnUiThread {
                frameworkOn = on
                modules.clear()
                modules.addAll(found)
                adapter.notifyDataSetChanged()
                emptyText.text = error
                    ?: "No Xposed modules found among your installed apps.\nInstall a module APK normally, then it shows up here."
                invalidateOptionsMenu()
            }
        }.start()
    }

    private fun showOptions(m: XModule) {
        val opts = ArrayList<Opt>()
        if (!m.added) {
            opts.add(Opt("Add to virtual space") { act("Added") { BlackBoxCore.get().installXPModule(m.pkg) } })
        } else {
            opts.add(Opt(if (m.enabled) "Disable" else "Enable") {
                act(if (m.enabled) "Disabled" else "Enabled") { BlackBoxCore.get().setModuleEnable(m.pkg, !m.enabled) }
            })
            opts.add(Opt("Update (re-add)") { act("Updated") { BlackBoxCore.get().installXPModule(m.pkg) } })
            opts.add(Opt("Remove") { act("Removed") { BlackBoxCore.get().uninstallXPModule(m.pkg) } })
        }
        AlertDialog.Builder(this)
            .setTitle(m.label)
            .setItems(opts.map { it.label }.toTypedArray()) { _, i -> opts[i].run() }
            .show()
    }

    private fun act(doneText: String, block: () -> Unit) {
        Thread {
            var msg = "$doneText. Restart the virtual apps for it to take effect."
            try {
                block()
            } catch (t: Throwable) {
                msg = "Failed: " + t.javaClass.simpleName + ": " + t.message
            }
            runOnUiThread {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                refresh()
            }
        }.start()
    }

    companion object {
        private const val MENU_FRAMEWORK = 1
    }
}
