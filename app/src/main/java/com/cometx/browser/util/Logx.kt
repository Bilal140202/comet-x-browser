package com.cometx.browser.util

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** Small logging facade. Secrets must never be passed here. */
object Logx {
    private const val TAG = "CometX"
    var verbose: Boolean = false

    fun d(msg: String) { if (verbose) Log.d(TAG, msg.take(600)) }
    fun i(msg: String) { Log.i(TAG, msg.take(600)) }
    fun w(msg: String) { Log.w(TAG, msg.take(600)) }
    fun e(msg: String, t: Throwable? = null) { Log.e(TAG, msg.take(600), t) }
}

object Json {
    /** Flat key/value builder: Json.obj("a", 1, "b", "x"). Nulls become JSON null. */
    fun obj(vararg args: Any?): JSONObject {
        require(args.size % 2 == 0) { "Json.obj needs key/value pairs" }
        val o = JSONObject()
        var i = 0
        while (i < args.size) {
            o.put(args[i] as String, args[i + 1] ?: JSONObject.NULL)
            i += 2
        }
        return o
    }

    fun arr(items: List<Any?>): JSONArray {
        val a = JSONArray()
        for (i in items) a.put(i ?: JSONObject.NULL)
        return a
    }

    /** Best-effort string; never throws. */
    fun str(o: JSONObject?, key: String, def: String = ""): String =
        if (o == null || o.isNull(key)) def else o.optString(key, def)

    fun int(o: JSONObject?, key: String, def: Int = 0): Int =
        if (o == null || o.isNull(key)) def else o.optInt(key, def)

    fun bool(o: JSONObject?, key: String, def: Boolean = false): Boolean =
        if (o == null || o.isNull(key)) def else o.optBoolean(key, def)

    fun double(o: JSONObject?, key: String, def: Double = 0.0): Double =
        if (o == null || o.isNull(key)) def else o.optDouble(key, def)

    /** Attempts to parse; returns null instead of throwing. */
    fun parseOrNull(raw: String?): JSONObject? {
        if (raw == null) return null
        return try { JSONObject(raw) } catch (_: Exception) { null }
    }

    fun parseArrayOrNull(raw: String?): JSONArray? {
        if (raw == null) return null
        return try { JSONArray(raw) } catch (_: Exception) { null }
    }

    /** Safely embed a Kotlin string inside generated JavaScript as a quoted literal. */
    fun jsString(s: String): String = JSONObject.quote(s)
}
