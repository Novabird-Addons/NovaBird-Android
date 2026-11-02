package org.novabird.feature.ai.assist

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.novabird.feature.vectors.ai.DigestEntry
import org.novabird.feature.vectors.ai.DigestTask

/**
 * Sorted mails of the overview (key: language, Message-ID and text hash), kept 10 days and at most 3000, like the
 * desktop
 * (ai/prep.js). One JSON file in the app's files; only on this phone.
 */
class DigestCache(private val file: File, private val now: () -> Long = System::currentTimeMillis) {
    constructor(context: Context) : this(File(context.filesDir, "novabird-ai/digest.json"))

    private class Item(val entry: DigestEntry, var seen: Long)

    private var items: MutableMap<String, Item>? = null
    private var dirty = false

    @Synchronized
    fun get(key: String): DigestEntry? = load()[key]?.let {
        it.seen = now()
        dirty = true
        it.entry
    }

    @Synchronized
    fun put(key: String, entry: DigestEntry) {
        load()[key] = Item(entry, now())
        dirty = true
    }

    /** Writes the file (after an overview), without entries older than 10 days and beyond the newest 3000. */
    @Synchronized
    fun save() {
        val map = load()
        if (!dirty) return
        val limit = now() - KEEP_MS
        map.entries.removeAll { it.value.seen < limit }
        if (map.size > MAX_ENTRIES) {
            map.entries.sortedBy { it.value.seen }.take(map.size - MAX_ENTRIES).forEach { map.remove(it.key) }
        }
        val json = JSONObject()
        for ((k, v) in map) json.put(k, toJson(v))
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(file)
        dirty = false
    }

    private fun load(): MutableMap<String, Item> = items ?: run {
        val map = mutableMapOf<String, Item>()
        runCatching {
            if (file.isFile) {
                val json = JSONObject(file.readText())
                for (k in json.keys()) fromJson(json.getJSONObject(k))?.let { map[k] = it }
            }
        }
        items = map
        map
    }

    private fun toJson(item: Item) = JSONObject().apply {
        put("cat", item.entry.cat)
        put("who", item.entry.who)
        put("what", item.entry.what)
        put("seen", item.seen)
        put(
            "tasks",
            JSONArray().apply {
                item.entry.tasks.forEach { put(JSONObject().put("title", it.title).put("due", it.due)) }
            },
        )
    }

    private fun fromJson(o: JSONObject): Item? = runCatching {
        val tasks = o.optJSONArray("tasks") ?: JSONArray()
        Item(
            DigestEntry(
                cat = o.getString("cat"),
                who = o.optString("who"),
                what = o.optString("what"),
                tasks = (0 until tasks.length()).map { tasks.getJSONObject(it) }
                    .map { DigestTask(it.optString("title"), it.optString("due")) },
            ),
            o.optLong("seen"),
        )
    }.getOrNull()

    private companion object {
        const val KEEP_MS = 10 * 86_400_000L
        const val MAX_ENTRIES = 3000
    }
}
