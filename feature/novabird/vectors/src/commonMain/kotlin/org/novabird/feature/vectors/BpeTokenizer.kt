package org.novabird.feature.vectors

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Byte-level BPE after the model's tokenizer.json (jina-de: no normalizer, RoBERTa frame `<s> … </s>`).
 * Port of NovaBird Helper `Tokenizer.cs`; must give the same ids as HF tokenizers and transformers.js.
 */
class BpeTokenizer(tokenizerJson: String) {
    private val vocab: Map<String, Int>
    private val ranks = HashMap<String, Int>()
    private val special = ArrayList<Special>()
    private val cache = HashMap<String, IntArray>()
    private val cls: Int
    private val sep: Int
    private val unk: Int

    private class Special(val content: String, val id: Int, val lstrip: Boolean, val rstrip: Boolean)

    init {
        val root = Json.parseToJsonElement(tokenizerJson).jsonObject
        val model = root.getValue("model").jsonObject
        require(model.getValue("type").jsonPrimitive.content == "BPE") { "only BPE tokenizers" }
        require(root["normalizer"].let { it == null || it is JsonNull }) { "normalizer not supported" }
        vocab = model.getValue("vocab").jsonObject.mapValues { it.value.jsonPrimitive.int }
        var rank = 0
        for (m in model.getValue("merges").jsonArray) {
            val pair = if (m is JsonArray) {
                m[0].jsonPrimitive.content + SEP + m[1].jsonPrimitive.content
            } else {
                val s = m.jsonPrimitive.content
                val i = s.indexOf(' ')
                s.substring(0, i) + SEP + s.substring(i + 1)
            }
            ranks.getOrPut(pair) { rank }
            rank++
        }
        for (t in root.getValue("added_tokens").jsonArray) {
            val o = t.jsonObject
            if (o.getValue("special").jsonPrimitive.boolean) {
                special += Special(
                    o.getValue("content").jsonPrimitive.content,
                    o.getValue("id").jsonPrimitive.int,
                    o.flag("lstrip"),
                    o.flag("rstrip"),
                )
            }
        }
        val post = root.getValue("post_processor").jsonObject
        cls = post.getValue("cls").jsonArray[1].jsonPrimitive.int
        sep = post.getValue("sep").jsonArray[1].jsonPrimitive.int
        unk = vocab["<unk>"] ?: UNK_FALLBACK
    }

    private fun JsonObject.flag(name: String) = this[name]?.jsonPrimitive?.boolean ?: false

    /** Token ids with `<s> … </s>`, truncated to [maxTokens] (frame included). */
    fun encode(text: String, maxTokens: Int): LongArray {
        val ids = ArrayList<Int>()
        for ((part, specialId) in splitSpecial(text)) {
            if (part == null) {
                ids += specialId
                continue
            }
            for (m in PIECES.findAll(part)) word(m.value).forEach { ids += it }
            if (ids.size >= maxTokens) break
        }
        val keep = minOf(ids.size, maxOf(0, maxTokens - 2))
        val out = LongArray(keep + 2)
        out[0] = cls.toLong()
        for (i in 0 until keep) out[i + 1] = ids[i].toLong()
        out[keep + 1] = sep.toLong()
        return out
    }

    // Special tokens in the text (e.g. a literal "</s>") are recognized as such, as in tokenizers
    private fun splitSpecial(text: String): List<Pair<String?, Int>> {
        val parts = ArrayList<Pair<String?, Int>>()
        var pos = 0
        while (pos < text.length) {
            var best = -1
            var bestIdx = -1
            for (s in special.indices) {
                val idx = text.indexOf(special[s].content, pos)
                val better = bestIdx < 0 || idx < bestIdx ||
                    (idx == bestIdx && special[s].content.length > special[best].content.length)
                if (idx >= 0 && better) {
                    best = s
                    bestIdx = idx
                }
            }
            if (best < 0) {
                parts += text.substring(pos) to -1
                break
            }
            val sp = special[best]
            var start = bestIdx
            var end = bestIdx + sp.content.length
            if (sp.lstrip) while (start > pos && text[start - 1].isWhitespace()) start--
            if (sp.rstrip) while (end < text.length && text[end].isWhitespace()) end++
            if (start > pos) parts += text.substring(pos, start) to -1
            parts += null to sp.id
            pos = end
        }
        return parts
    }

    private fun word(piece: String): IntArray {
        synchronized(cache) { cache[piece]?.let { return it } }
        var symbols = piece.encodeToByteArray().map { BYTE_CHAR[it.toInt() and 0xFF].toString() }
        while (symbols.size > 1) {
            var bestRank = Int.MAX_VALUE
            var bestI = -1
            for (i in 0 until symbols.size - 1) {
                val r = ranks[symbols[i] + SEP + symbols[i + 1]]
                if (r != null && r < bestRank) {
                    bestRank = r
                    bestI = i
                }
            }
            if (bestI < 0) break
            val a = symbols[bestI]
            val b = symbols[bestI + 1]
            val merged = ArrayList<String>(symbols.size)
            var i = 0
            while (i < symbols.size) {
                if (i < symbols.size - 1 && symbols[i] == a && symbols[i + 1] == b) {
                    merged += a + b
                    i += 2
                } else {
                    merged += symbols[i]
                    i++
                }
            }
            symbols = merged
        }
        val ids = IntArray(symbols.size) { vocab[symbols[it]] ?: unk }
        synchronized(cache) {
            if (cache.size > CACHE_LIMIT) cache.clear()
            cache[piece] = ids
        }
        return ids
    }

    private companion object {
        const val SEP = "\u0000"
        const val UNK_FALLBACK = 3
        const val CACHE_LIMIT = 50_000

        // Pre-split as ByteLevel (GPT-2 pattern); \s must be Unicode whitespace (NBSP etc.) like .NET and tokenizers
        val PIECES = unicodeRegex(
            """'s|'t|'re|'ve|'m|'ll|'d| ?\p{L}+| ?\p{N}+| ?[^\s\p{L}\p{N}]+|\s+(?!\S)|\s+""",
        )

        // GPT-2: every byte becomes a printable character
        val BYTE_CHAR: CharArray = run {
            val bs = (('!'.code..'~'.code) + (0xA1..0xAC) + (0xAE..0xFF)).toSet()
            var n = 0
            CharArray(256) { b -> if (b in bs) b.toChar() else (256 + n++).toChar() }
        }
    }
}

/** Regex whose `\s` matches all Unicode whitespace. */
internal expect fun unicodeRegex(pattern: String): Regex
