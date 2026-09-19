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
import com.cometx.browser.browse.BookmarksStore
import com.google.android.material.appbar.MaterialToolbar

/**
 * BookmarksActivity (v2.0.0) — saved pages.
 *
 * Tap = open in a new browser tab (result to MainActivity); long-press =
 * delete. Ported from the Zerium codebase (GPL-3.0, same owner).
 */
class BookmarksActivity : AppCompatActivity() {

    private lateinit var store: BookmarksStore
    private lateinit var adapter: ArrayAdapter<String>
    private val rows = ArrayList<BookmarksStore.Entry>()
    private val labels = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setTitle(R.string.menu_bookmarks)
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        toolbar.setNavigationOnClickListener { finish() }

        store = BookmarksStore(this)
        val list = findViewById<ListView>(R.id.list)
        adapter = object : ArrayAdapter<String>(
            this, android.R.layout.simple_list_item_2, android.R.id.text1, labels
        ) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                v.findViewById<TextView>(android.R.id.text1).text = rows[position].title
                v.findViewById<TextView>(android.R.id.text2).text = rows[position].url
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
            Toast.makeText(this, R.string.bookmark_removed, Toast.LENGTH_SHORT).show()
            reload()
            true
        }
        findViewById<TextView>(R.id.empty).setText(R.string.bookmarks_empty)
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
