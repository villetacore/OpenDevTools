package com.opendevtools.core.json

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Lenient readers for server payloads. Every accessor returns a default rather than throwing,
 * because both APIs omit fields freely and a single missing key must not fail a whole refresh.
 */
object JsonAccess {

    fun parse(text: String): JsonElement? =
        runCatching { JsonParser.parseString(text) }.getOrNull()?.takeIf { !it.isJsonNull }

    fun parseObject(text: String): JsonObject? = parse(text) as? JsonObject

    fun parseArray(text: String): JsonArray? = parse(text) as? JsonArray
}

fun JsonElement?.notNull(): JsonElement? = this?.takeIf { !it.isJsonNull }

/**
 * Bitwarden servers have shipped both PascalCase and camelCase payloads over the years, and
 * Vaultwarden follows whichever schema version it targets, so lookups fall back to a
 * case-insensitive scan. Proxmox is consistently lower case and never reaches the fallback.
 */
fun JsonObject.field(name: String): JsonElement? {
    get(name).notNull()?.let { return it }
    if (has(name)) return null
    for (entry in entrySet()) {
        if (entry.key.equals(name, ignoreCase = true)) return entry.value.notNull()
    }
    return null
}

fun JsonObject.str(name: String): String? =
    field(name)?.let { runCatching { it.asString }.getOrNull() }

fun JsonObject.strOr(name: String, fallback: String): String = str(name) ?: fallback

fun JsonObject.int(name: String): Int = intOrNull(name) ?: 0

fun JsonObject.intOrNull(name: String): Int? =
    field(name)?.let { runCatching { it.asInt }.getOrNull() }

fun JsonObject.long(name: String): Long = longOrNull(name) ?: 0L

fun JsonObject.longOrNull(name: String): Long? =
    field(name)?.let { runCatching { it.asLong }.getOrNull() }

fun JsonObject.dbl(name: String): Double = dblOrNull(name) ?: 0.0

fun JsonObject.dblOrNull(name: String): Double? =
    field(name)?.let { runCatching { it.asDouble }.getOrNull() }

/** Proxmox encodes booleans as 0/1 integers, Bitwarden as real JSON booleans. */
fun JsonObject.bool(name: String): Boolean = boolOrNull(name) ?: false

/**
 * Gson does not throw on `JsonPrimitive(1).asBoolean` - it parses the string "1" and answers
 * `false` - so the primitive's actual kind has to be inspected rather than guessed by trial.
 */
fun JsonObject.boolOrNull(name: String): Boolean? {
    val primitive = field(name)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
    return when {
        primitive.isBoolean -> primitive.asBoolean
        primitive.isNumber -> runCatching { primitive.asInt != 0 }.getOrNull()
        else -> when (primitive.asString.trim().lowercase()) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> null
        }
    }
}

fun JsonObject.obj(name: String): JsonObject? = field(name)?.takeIf { it.isJsonObject }?.asJsonObject

fun JsonObject.array(name: String): List<JsonObject> =
    field(name)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it as? JsonObject }.orEmpty()

/** Numbers are rendered as their text form; every caller here wants them that way. */
fun JsonObject.stringArray(name: String): List<String> =
    field(name)?.takeIf { it.isJsonArray }?.asJsonArray
        ?.mapNotNull { runCatching { it.asString }.getOrNull() }
        .orEmpty()

fun JsonElement?.asObjectList(): List<JsonObject> =
    (this as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

fun JsonElement?.asObjectOrNull(): JsonObject? = this?.takeIf { it.isJsonObject }?.asJsonObject

fun JsonElement?.asStringOrNull(): String? =
    this?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() }
