package com.cometx.browser.browse

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * BookmarksStore (v2.0.0) — saved pages.
 *
 * Ported from the Zerium codebase (GPL-3.0, same owner). Incognito tabs never
 * write here (enforced by callers, mirrored from Zerium's design).
 */
class BookmarksStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB, null, VERSION) {

    data class Entry(val id: Long, val url: String, val title: String, val added: String)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE bookmarks(id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT UNIQUE, title TEXT, added TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 schema; nothing to migrate yet
    }

    fun add(url: String?, title: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        if (contains(url)) return false
        val cv = ContentValues().apply {
            put("url", url)
            put("title", if (title.isNullOrBlank()) url else title)
            put("added", now())
        }
        writableDatabase.insertWithOnConflict("bookmarks", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        return true
    }

    fun remove(id: Long): Boolean =
        writableDatabase.delete("bookmarks", "id=?", arrayOf(id.toString())) > 0

    fun contains(url: String?): Boolean {
        if (url == null) return false
        readableDatabase.rawQuery("SELECT 1 FROM bookmarks WHERE url=?", arrayOf(url)).use { c ->
            return c.moveToFirst()
        }
    }

    fun all(): List<Entry> {
        val out = ArrayList<Entry>()
        readableDatabase.rawQuery("SELECT id,url,title,added FROM bookmarks ORDER BY id DESC", null).use { c ->
            while (c.moveToNext()) {
                out.add(Entry(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: ""))
            }
        }
        return out
    }

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    companion object {
        private const val DB = "cometx_bookmarks.db"
        private const val VERSION = 1
    }
}
