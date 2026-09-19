package com.cometx.browser.ui

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.cometx.browser.R
import com.google.android.material.appbar.MaterialToolbar

/**
 * DownloadsActivity (v2.0.0) — downloads managed by the system
 * DownloadManager (page downloads AND agent-initiated ones).
 *
 * Tap = open with a capable app; long-press = remove the entry. Ported from
 * the Zerium codebase (GPL-3.0, same owner).
 */
class DownloadsActivity : AppCompatActivity() {

    private class Row {
        var id: Long = 0
        var title: String = ""
        var status: String = ""
    }

    private lateinit var dm: DownloadManager
    private lateinit var adapter: ArrayAdapter<String>
    private val rows = ArrayList<Row>()
    private val labels = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setTitle(R.string.menu_downloads)
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        toolbar.setNavigationOnClickListener { finish() }
        dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        val list = findViewById<ListView>(R.id.list)
        adapter = object : ArrayAdapter<String>(
            this, android.R.layout.simple_list_item_2, android.R.id.text1, labels
        ) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val r = rows[position]
                v.findViewById<TextView>(android.R.id.text1).text = r.title
                v.findViewById<TextView>(android.R.id.text2).text = r.status
                return v
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val r = rows[position]
            runCatching {
                val uri = dm.getUriForDownloadedFile(r.id)
                if (uri != null) {
                    val intent = Intent(Intent.ACTION_VIEW)
                    intent.setDataAndType(uri, dm.getMimeTypeForDownloadedFile(r.id))
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(intent)
                } else {
                    Toast.makeText(this, R.string.download_not_ready, Toast.LENGTH_SHORT).show()
                }
            }.onFailure {
                Toast.makeText(this, R.string.download_open_failed, Toast.LENGTH_SHORT).show()
            }
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            runCatching { dm.remove(rows[position].id) }
            Toast.makeText(this, R.string.download_removed, Toast.LENGTH_SHORT).show()
            reload()
            true
        }
        findViewById<TextView>(R.id.empty).setText(R.string.downloads_empty)
        reload()
    }

    private fun reload() {
        rows.clear()
        labels.clear()
        runCatching {
            dm.query(DownloadManager.Query()).use { c ->
                if (c != null) {
                    val idCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                    val titleCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)
                    val statusCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                    while (c.moveToNext()) {
                        val r = Row()
                        r.id = c.getLong(idCol)
                        r.title = c.getString(titleCol) ?: ""
                        r.status = when (c.getInt(statusCol)) {
                            DownloadManager.STATUS_SUCCESSFUL -> getString(R.string.download_done)
                            DownloadManager.STATUS_FAILED -> getString(R.string.download_status_failed)
                            DownloadManager.STATUS_PAUSED -> getString(R.string.download_paused)
                            else -> getString(R.string.download_running)
                        }
                        rows.add(r)
                        labels.add(r.title)
                    }
                }
            }
        }
        adapter.notifyDataSetChanged()
        findViewById<View>(R.id.empty).visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }
}
