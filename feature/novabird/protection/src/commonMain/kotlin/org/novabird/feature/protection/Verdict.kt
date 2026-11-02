package org.novabird.feature.protection

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Rating levels of the add-on (`normal`, `suspicious`, `dangerous`). */
enum class ProtectionLevel(val id: String) {
    NORMAL("normal"),
    SUSPICIOUS("suspicious"),
    DANGEROUS("dangerous"),
    ;

    companion object {
        fun fromId(id: String): ProtectionLevel = entries.firstOrNull { it.id == id } ?: NORMAL
    }
}

/** One finding of the check: signal id, its weight in the score and the parameters for its text. */
data class Signal(val id: String, val weight: Double, val params: List<String>)

/** Result of `analyze()`. [json] is the add-on's verdict as it came, for storing and for [ProtectionEngine.reasons]. */
data class Verdict(
    val score: Int,
    val level: ProtectionLevel,
    val signals: List<Signal>,
    val json: JsonObject,
) {
    companion object {
        fun fromJson(json: JsonObject) = Verdict(
            score = json.getValue("score").jsonPrimitive.content.toDouble().toInt(),
            level = ProtectionLevel.fromId(json.getValue("level").jsonPrimitive.content),
            signals = json.getValue("signals").jsonArray.map { element ->
                val o = element.jsonObject
                Signal(
                    id = o.getValue("id").jsonPrimitive.content,
                    weight = o.getValue("weight").jsonPrimitive.content.toDouble(),
                    params = o["params"]?.jsonArray?.map { param ->
                        (param as? JsonPrimitive)?.content ?: param.toString()
                    }.orEmpty(),
                )
            },
            json = json,
        )
    }
}

/** Features (input of `analyze()`) and the verdict of one message. */
data class CheckResult(val features: JsonObject, val verdict: Verdict)

/** Explanation of one signal, ready to show. */
data class Reason(val id: String, val weight: Double, val text: String)
