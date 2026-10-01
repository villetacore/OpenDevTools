package com.opendevtools.core.json

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JsonAccessTest {

    private fun obj(json: String) = JsonParser.parseString(json).asJsonObject

    @Test
    fun `reads an exact key`() {
        val o = obj("""{"name":"pve1","cores":4,"cpu":0.25}""")
        assertEquals("pve1", o.str("name"))
        assertEquals(4, o.int("cores"))
        assertEquals(0.25, o.dbl("cpu"))
    }

    @Test
    fun `falls back to a case-insensitive match`() {
        // Bitwarden servers have shipped both PascalCase and camelCase over the years.
        val o = obj("""{"Name":"Personal","RevisionDate":"2026-01-01T00:00:00Z"}""")
        assertEquals("Personal", o.str("name"))
        assertEquals("2026-01-01T00:00:00Z", o.str("revisionDate"))
    }

    @Test
    fun `an explicit null is not resolved through the fallback`() {
        // The key is present but null: that is an answer, not a reason to keep searching.
        val o = obj("""{"name":null,"NAME":"shadow"}""")
        assertNull(o.str("name"))
    }

    @Test
    fun `missing keys yield defaults rather than throwing`() {
        val o = obj("""{}""")
        assertNull(o.str("absent"))
        assertEquals(0, o.int("absent"))
        assertEquals(0L, o.long("absent"))
        assertEquals(0.0, o.dbl("absent"))
        assertFalse(o.bool("absent"))
        assertNull(o.obj("absent"))
        assertTrue(o.array("absent").isEmpty())
        assertTrue(o.stringArray("absent").isEmpty())
    }

    @Test
    fun `booleans accept the JSON, the integer and the string form`() {
        assertTrue(obj("""{"shared":true}""").bool("shared"))
        assertFalse(obj("""{"shared":false}""").bool("shared"))
        // Proxmox writes flags as 0/1 integers.
        assertTrue(obj("""{"shared":1}""").bool("shared"))
        assertFalse(obj("""{"shared":0}""").bool("shared"))
        // Some fields arrive as quoted flags instead.
        assertTrue(obj("""{"shared":"1"}""").bool("shared"))
        assertFalse(obj("""{"shared":"0"}""").bool("shared"))
    }

    @Test
    fun `a value that is not a flag at all reads as unset`() {
        assertNull(obj("""{"shared":"maybe"}""").boolOrNull("shared"))
        assertFalse(obj("""{"shared":"maybe"}""").bool("shared"))
    }

    @Test
    fun `a wrongly typed value degrades to the default instead of failing`() {
        val o = obj("""{"cores":"not a number"}""")
        assertEquals(0, o.int("cores"))
        assertNull(o.intOrNull("cores"))
    }

    @Test
    fun `object arrays skip entries of the wrong shape`() {
        val o = obj("""{"rows":[{"a":1},"scalar",{"b":2}]}""")
        assertEquals(2, o.array("rows").size)
    }

    @Test
    fun `string arrays render numbers as text rather than dropping them`() {
        val o = obj("""{"names":["x",7,"y"]}""")
        assertEquals(listOf("x", "7", "y"), o.stringArray("names"))
    }

    @Test
    fun `parse returns null for malformed input`() {
        assertNull(JsonAccess.parseObject("{ not json"))
        assertNull(JsonAccess.parseObject("[1,2,3]"))
    }
}
