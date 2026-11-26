package com.calllogeditor.app

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog.Calls as C
import android.provider.ContactsContract.PhoneLookup

data class CallEntry(
    val id: Long = 0,
    val number: String = "",
    val name: String = "",
    val type: Int = C.OUTGOING_TYPE,
    val date: Long = System.currentTimeMillis(),
    val duration: Long = 0
)

object Contacts {
    fun has(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun lookup(ctx: Context, number: String): String? {
        if (!has(ctx) || number.isBlank()) return null
        return try {
            ctx.contentResolver.query(
                Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                arrayOf(PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (_: Exception) {
            null
        }
    }
}

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

    /** Writes the contact name into every entry whose saved name differs. Returns how many changed. */
    fun syncNames(ctx: Context, list: List<CallEntry>): Int {
        var n = 0
        val cache = HashMap<String, String?>()
        list.forEach { e ->
            val nm = cache.getOrPut(e.number) { Contacts.lookup(ctx, e.number) }
            if (nm != null && nm != e.name && save(ctx, e.copy(name = nm))) n++
        }
        return n
    }

    fun importAll(ctx: Context, items: List<CallEntry>, existing: List<CallEntry>): Int {
        val keys = existing.map { "${it.number}|${it.date}" }.toMutableSet()
        var n = 0
        items.forEach {
            if (keys.add("${it.number}|${it.date}") && save(ctx, it.copy(id = 0))) n++
        }
        return n
    }
}

object Csv {
    private fun esc(s: String) =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    fun export(list: List<CallEntry>) = buildString {
        append("number,name,type,date,duration\n")
        list.forEach { append("${esc(it.number)},${esc(it.name)},${it.type},${it.date},${it.duration}\n") }
    }

    fun parse(text: String): List<CallEntry> {
        val rows = mutableListOf<List<String>>()
        var cur = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { sb.append('"'); i++ } else quoted = false
                } else sb.append(ch)
            } else {
                when (ch) {
                    '"' -> quoted = true
                    ',' -> { cur.add(sb.toString()); sb.clear() }
                    '\n' -> { cur.add(sb.toString()); sb.clear(); rows.add(cur); cur = mutableListOf() }
                    '\r' -> {}
                    else -> sb.append(ch)
                }
            }
            i++
        }
        if (sb.isNotEmpty() || cur.isNotEmpty()) { cur.add(sb.toString()); rows.add(cur) }
        return rows.drop(1).mapNotNull { r ->
            if (r.size < 5) return@mapNotNull null
            val t = r[2].trim().toIntOrNull()
            val d = r[3].trim().toLongOrNull()
            val du = r[4].trim().toLongOrNull()
            if (t == null || d == null || du == null) null else CallEntry(0, r[0], r[1], t, d, du)
        }
    }
}
