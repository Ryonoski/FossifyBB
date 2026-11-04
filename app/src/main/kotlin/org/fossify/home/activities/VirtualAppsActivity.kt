package org.fossify.home.activities

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

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
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        Thread {
            val result = ArrayList<VApp>()
            var error: String? = null
            try {
                val cursor = contentResolver.query(
                    Uri.parse("content://$BB_PKG.bridge"), null, null, null, null
                )
                if (cursor == null) {
                    val info = packageManager.resolveContentProvider("$BB_PKG.bridge", 0)
                    error = if (info == null) {
                        "Provider not found: NewBlackbox is not installed, or this app cannot see its provider."
                    } else {
                        "Provider found but returned nothing: it most likely crashed inside NewBlackbox."
                    }
                } else {
                    cursor.use { c ->
                        while (c.moveToNext()) {
                            val pkg = c.getString(0)
                            if (pkg == "__error__") {
                                error = "NewBlackbox error: " + c.getString(1)
                                break
                            }
                            result.add(VApp(pkg, c.getString(1), c.getInt(2)))
                        }
                    }
                }
            } catch (e: Exception) {
                error = "Can't reach NewBlackbox bridge (" + e.javaClass.simpleName + "): " + e.message
            }
            result.sortBy { it.label.lowercase() }
            runOnUiThread {
                apps.clear()
                apps.addAll(result)
                adapter.notifyDataSetChanged()
                emptyText.text = error ?: "No virtual apps yet. Clone some in NewBlackbox."
            }
        }.start()
    }

    private fun launch(app: VApp) {
        try {
            startActivity(
                Intent().setClassName(BB_PKG, BB_ACTIVITY)
                    .putExtra("pkg", app.pkg)
                    .putExtra("user", app.user)
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Can't launch: " + e.javaClass.simpleName, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val BB_PKG = "top.niunaijun.blackbox"
        private const val BB_ACTIVITY = "top.niunaijun.blackboxa.bridge.BridgeLaunchActivity"
    }
}
