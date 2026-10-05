package com.vibe.music.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/** Bezpečná navigace v JSONu: `obj.path("a", "b", 0, "c")`. */
fun JsonElement?.path(vararg keys: Any): JsonElement? {
    var cur: JsonElement? = this
    for (k in keys) {
        cur = when {
            k is String && cur is JsonObject -> cur[k]
            k is Int && cur is JsonArray -> cur.getOrNull(k)
            else -> null
        }
    }
    return cur
}

val JsonElement?.obj: JsonObject? get() = this as? JsonObject
val JsonElement?.arr: JsonArray? get() = this as? JsonArray
val JsonElement?.str: String? get() = (this as? JsonPrimitive)?.contentOrNull
val JsonElement?.num: Double? get() = (this as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }

/** Najde rekurzivně všechny objekty uložené pod klíčem [key]. */
fun JsonElement.findAll(key: String, out: MutableList<JsonObject> = mutableListOf()): List<JsonObject> {
    when (this) {
        is JsonObject -> for ((k, v) in this) {
            if (k == key && v is JsonObject) out += v else v.findAll(key, out)
        }
        is JsonArray -> forEach { it.findAll(key, out) }
        else -> Unit
    }
    return out
}
