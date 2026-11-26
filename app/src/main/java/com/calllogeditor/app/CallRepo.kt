package com.calllogeditor.app

import android.content.ContentValues
import android.content.Context
import android.provider.CallLog.Calls as C

data class CallEntry(
    val id: Long = 0,
    val number: String = "",
    val name: String = "",
    val type: Int = C.OUTGOING_TYPE,
    val date: Long = System.currentTimeMillis(),
    val duration: Long = 0
)

object CallRepo {
    fun load(ctx: Context): List<CallEntry> {
        val out = mutableListOf<CallEntry>()
        try {
            ctx.contentResolver.query(
                C.CONTENT_URI,
                arrayOf(C._ID, C.NUMBER, C.CACHED_NAME, C.TYPE, C.DATE, C.DURATION),
                null, null, "${C.DATE} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    out += CallEntry(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getInt(3), c.getLong(4), c.getLong(5))
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun values(e: CallEntry) = ContentValues().apply {
        put(C.NUMBER, e.number)
        put(C.CACHED_NAME, e.name)
        put(C.TYPE, e.type)
        put(C.DATE, e.date)
        put(C.DURATION, e.duration)
        put(C.NEW, 0)
    }

    fun save(ctx: Context, e: CallEntry): Boolean = try {
        if (e.id == 0L) ctx.contentResolver.insert(C.CONTENT_URI, values(e)) != null
        else ctx.contentResolver.update(C.CONTENT_URI, values(e), "${C._ID}=?", arrayOf(e.id.toString())) > 0
    } catch (_: Exception) {
        false
    }

    fun delete(ctx: Context, ids: Set<Long>): Boolean = try {
        ids.forEach { ctx.contentResolver.delete(C.CONTENT_URI, "${C._ID}=?", arrayOf(it.toString())) }
        true
    } catch (_: Exception) {
        false
    }
}
