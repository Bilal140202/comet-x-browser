package com.cometx.browser.browse

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * HistoryStore (v2.0.0) — visited pages.
 *
 * Ported from the Zerium codebase (GPL-3.0, same owner). Incognito tabs and
 * start-page/data URLs never write here (enforced by callers; add() also
 * guards defensively). Powers the History screen and the start page's
 * most-visited shortcut tiles ([StartPageLogic.autoTiles]).
 */
class HistoryStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB, null, VERSION) {

    data class Entry(val id: Long, val url: String, val title: String, val visited: String)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE history(id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT, title TEXT, visited TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 schema; nothing to migrate yet
    }

    fun add(url: String?, title: String?) {
        if (url.isNullOrEmpty() || url.startsWith("data:")) return
        val cv = ContentValues().apply {
            put("url", url)
            put("title", if (title.isNullOrBlank()) url else title)
            put("visited", timestamp())
        }
        writableDatabase.insert("history", null, cv)
    }

    fun remove(id: Long): Boolean =
        writableDatabase.delete("history", "id=?", arrayOf(id.toString())) > 0

    fun all(): List<Entry> {
        val out = ArrayList<Entry>()
        readableDatabase.rawQuery(
            "SELECT id,url,title,visited FROM history ORDER BY id DESC LIMIT 500", null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(Entry(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: ""))
            }
        }
        return out
    }

    /**
     * Most-visited distinct URLs for the start-page shortcuts, ordered by
     * visit count. Titles keep their newest version.
     */
    fun topSites(limit: Int): List<Entry> {
        val out = ArrayList<Entry>()
        readableDatabase.rawQuery(
            "SELECT MIN(id), url, MAX(title), MAX(visited) FROM history " +
                "GROUP BY url ORDER BY COUNT(*) DESC LIMIT ?",
            arrayOf(limit.coerceAtLeast(1).toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(Entry(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: ""))
            }
        }
        return out
    }

    fun clear() {
        writableDatabase.delete("history", null, null)
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

    companion object {
        private const val DB = "cometx_history.db"
        private const val VERSION = 1
    }
}
