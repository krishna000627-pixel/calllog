package com.calllogeditor.app

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog.Calls as C
import android.provider.ContactsContract.CommonDataKinds.Phone
import org.json.JSONArray
import org.json.JSONObject

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

    /** Digits only, last 10: +91 98765 43210, 098765 43210, 91-9876543210 and 9876543210 all match. */
    fun norm(s: String): String {
        val d = s.filter { it.isDigit() }
        return if (d.length > 10) d.takeLast(10) else d
    }

    /** One pass over all contact numbers: normalized number -> contact name. */
    fun loadMap(ctx: Context): Map<String, String> {
        if (!has(ctx)) return emptyMap()
        val m = HashMap<String, String>()
        try {
            ctx.contentResolver.query(Phone.CONTENT_URI, arrayOf(Phone.NUMBER, Phone.DISPLAY_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val n = norm(c.getString(0) ?: "")
                    val nm = c.getString(1)
                    if (n.isNotEmpty() && nm != null) m.putIfAbsent(n, nm)
                }
            }
        } catch (_: Exception) {
        }
        return m
    }

    /** Reads number and name from a row returned by the contact picker. */
    fun picked(ctx: Context, uri: Uri): Pair<String, String>? = try {
        ctx.contentResolver.query(uri, arrayOf(Phone.NUMBER, Phone.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) (it.getString(0) ?: "") to (it.getString(1) ?: "") else null
        }
    } catch (_: Exception) {
        null
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

    /** Writes contact names into the log. onlyBlank keeps names you typed yourself. Returns how many changed. */
    fun syncNames(ctx: Context, list: List<CallEntry>, map: Map<String, String>, onlyBlank: Boolean = false): Int {
        var n = 0
        list.forEach { e ->
            val nm = map[Contacts.norm(e.number)]
            if (nm != null && nm != e.name && (!onlyBlank || e.name.isBlank()) && save(ctx, e.copy(name = nm))) n++
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

/** Calls from [number] (any format) become [type] (or are deleted, or get [name]). */
data class Rule(
    val id: Long,
    val number: String,
    val type: Int,
    val name: String = "",
    val delete: Boolean = false,
    val enabled: Boolean = true,
    val since: Long = System.currentTimeMillis()
)

object Rules {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("rules", Context.MODE_PRIVATE)

    fun load(ctx: Context): List<Rule> = try {
        val a = JSONArray(sp(ctx).getString("list", "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Rule(o.getLong("id"), o.getString("number"), o.getInt("type"), o.optString("name"),
                o.optBoolean("delete"), o.optBoolean("enabled", true), o.optLong("since", 0))
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun save(ctx: Context, list: List<Rule>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("id", it.id).put("number", it.number).put("type", it.type).put("name", it.name)
                .put("delete", it.delete).put("enabled", it.enabled).put("since", it.since))
        }
        sp(ctx).edit().putString("list", a.toString()).apply()
    }

    fun matches(r: Rule, e: CallEntry): Boolean {
        val n = Contacts.norm(r.number)
        return n.isNotEmpty() && n == Contacts.norm(e.number)
    }

    /** onlyNew: only calls made after the rule was created. Returns how many calls changed. */
    fun apply(ctx: Context, rules: List<Rule>, entries: List<CallEntry>, onlyNew: Boolean): Int {
        var n = 0
        for (e in entries) {
            val r = rules.firstOrNull { it.enabled && matches(it, e) && (!onlyNew || e.date >= it.since) } ?: continue
            if (r.delete) {
                if (CallRepo.delete(ctx, setOf(e.id))) n++
            } else {
                val ne = e.copy(type = r.type, name = if (r.name.isNotBlank()) r.name else e.name)
                if (ne != e && CallRepo.save(ctx, ne)) n++
            }
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
