package com.opendevtools.core.json

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * A small builder for request payloads. Gson's reflective serializer is avoided on purpose:
 * the Bitwarden write API is picky about which keys may be absent versus explicitly null, and
 * building the tree by hand keeps that decision visible at the call site.
 */
class JsonWriter {

    private val root = JsonObject()

    fun put(name: String, value: String?): JsonWriter = apply {
        root.add(name, value?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
    }

    fun put(name: String, value: Int?): JsonWriter = apply {
        root.add(name, value?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
    }

    fun put(name: String, value: Long?): JsonWriter = apply {
        root.add(name, value?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
    }

    fun put(name: String, value: Boolean?): JsonWriter = apply {
        root.add(name, value?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE)
    }

    fun put(name: String, value: JsonObject?): JsonWriter = apply {
        root.add(name, value ?: JsonNull.INSTANCE)
    }

    fun put(name: String, value: JsonArray?): JsonWriter = apply {
        root.add(name, value ?: JsonNull.INSTANCE)
    }

    fun obj(): JsonObject = root

    override fun toString(): String = root.toString()

    companion object {

        fun build(block: JsonWriter.() -> Unit): JsonObject = JsonWriter().apply(block).obj()

        fun arrayOfObjects(items: List<JsonObject>): JsonArray =
            JsonArray().apply { items.forEach { add(it) } }

        fun arrayOfStrings(items: List<String>): JsonArray =
            JsonArray().apply { items.forEach { add(it) } }
    }
}
