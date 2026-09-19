package com.cometx.browser.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.cometx.browser.R
import com.cometx.browser.browse.HistoryStore
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * HistoryActivity (v2.0.0) — visited pages (incognito tabs never land here).
 *
 * Tap = open in a new browser tab (result to MainActivity); long-press =
 * delete entry; toolbar menu = clear all. Ported from the Zerium codebase
 * (GPL-3.0, same owner).
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var store: HistoryStore
    private lateinit var adapter: ArrayAdapter<String>
    private val rows = ArrayList<HistoryStore.Entry>()
    private val labels = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setTitle(R.string.menu_history)
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.menu_history)
        toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_clear) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.history_clear)
                    .setMessage(getString(R.string.history_empty))
                    .setPositiveButton(R.string.menu_clear_data) { _, _ ->
                        runCatching { store.clear() }
                        Toast.makeText(this, R.string.history_clear, Toast.LENGTH_SHORT).show()
                        reload()
                    }
                    .setNegativeButton(R.string.cancel_dialog, null)
                    .show()
                true
            } else false
        }

        store = HistoryStore(this)
        val list = findViewById<ListView>(R.id.list)
        adapter = object : ArrayAdapter<String>(
            this, android.R.layout.simple_list_item_2, android.R.id.text1, labels
        ) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                v.findViewById<TextView>(android.R.id.text1).text = rows[position].title
                v.findViewById<TextView>(android.R.id.text2).text =
                    "${rows[position].visited} · ${rows[position].url}"
                return v
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val intent = Intent()
            intent.putExtra("url", rows[position].url)
            setResult(RESULT_OK, intent)
            finish()
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            store.remove(rows[position].id)
            reload()
            true
        }
        findViewById<TextView>(R.id.empty).setText(R.string.history_empty)
        reload()
    }

    private fun reload() {
        rows.clear()
        rows.addAll(runCatching { store.all() }.getOrDefault(emptyList()))
        labels.clear()
        labels.addAll(rows.map { it.title })
        adapter.notifyDataSetChanged()
    }
}
