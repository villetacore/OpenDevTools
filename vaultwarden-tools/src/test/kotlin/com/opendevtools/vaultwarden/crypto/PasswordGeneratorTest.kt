package com.opendevtools.vaultwarden.crypto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PasswordGeneratorTest {

    /** Enough draws that a systematic omission shows up, but still instant. */
    private val samples = 200

    @Test
    fun `the requested length is honoured`() {
        listOf(8, 16, 32, 64).forEach { length ->
            val password = PasswordGenerator.generate(PasswordOptions(length = length))
            assertEquals(length, password.length, "length $length")
        }
    }

    @Test
    fun `a disabled character class never appears`() {
        val options = PasswordOptions(
            length = 24,
            useUppercase = false,
            useLowercase = true,
            useDigits = false,
            useSpecial = false,
            minDigits = 0,
            minSpecial = 0,
        )
        repeat(samples) {
            val password = PasswordGenerator.generate(options)
            assertTrue(password.all { it in 'a'..'z' }, password)
        }
    }

    @Test
    fun `the per-class minimums are met`() {
        val options = PasswordOptions(length = 20, minDigits = 3, minSpecial = 2)
        repeat(samples) {
            val password = PasswordGenerator.generate(options)
            assertTrue(password.count { it.isDigit() } >= 3, password)
            assertTrue(password.count { !it.isLetterOrDigit() } >= 2, password)
        }
    }

    @Test
    fun `every enabled class shows up at least once`() {
        val options = PasswordOptions(length = 16, minDigits = 1, minSpecial = 1)
        repeat(samples) {
            val password = PasswordGenerator.generate(options)
            assertTrue(password.any { it.isUpperCase() }, password)
            assertTrue(password.any { it.isLowerCase() }, password)
            assertTrue(password.any { it.isDigit() }, password)
            assertTrue(password.any { !it.isLetterOrDigit() }, password)
        }
    }

    @Test
    fun `ambiguous characters are left out when asked`() {
        val ambiguous = "Il1O0oB8S5Z2".toSet()
        val options = PasswordOptions(
            length = 32,
            useSpecial = false,
            minSpecial = 0,
            avoidAmbiguous = true,
        )
        repeat(samples) {
            val password = PasswordGenerator.generate(options)
            assertTrue(password.none { it in ambiguous }, password)
        }
    }

    @Test
    fun `a password without any character set is refused`() {
        val options = PasswordOptions(
            useUppercase = false,
            useLowercase = false,
            useDigits = false,
            useSpecial = false,
        )
        assertThrows(VaultCryptoException::class.java) { PasswordGenerator.generate(options) }
    }

    @Test
    fun `a length below the floor is raised rather than producing a stub`() {
        val password = PasswordGenerator.generate(PasswordOptions(length = 1))
        assertTrue(password.length >= PasswordGenerator.MIN_LENGTH, password)
    }

    @Test
    fun `successive passwords differ`() {
        val generated = (1..50).map { PasswordGenerator.generate(PasswordOptions(length = 20)) }
        assertEquals(generated.size, generated.toSet().size)
    }

    @Test
    fun `a passphrase has the requested number of words and separator`() {
        val options = PassphraseOptions(words = 5, separator = ".", capitalize = false, includeNumber = false)
        repeat(50) {
            val phrase = PasswordGenerator.generatePassphrase(options)
            assertEquals(5, phrase.split(".").size, phrase)
            assertTrue(phrase.none { it.isUpperCase() }, phrase)
            assertTrue(phrase.none { it.isDigit() }, phrase)
        }
    }

    @Test
    fun `a passphrase can be capitalised and carry a digit`() {
        val options = PassphraseOptions(words = 4, separator = "-", capitalize = true, includeNumber = true)
        repeat(50) {
            val phrase = PasswordGenerator.generatePassphrase(options)
            assertTrue(phrase.split("-").all { it.first().isUpperCase() }, phrase)
            assertTrue(phrase.any { it.isDigit() }, phrase)
        }
    }

    @Test
    fun `an empty separator falls back to a hyphen rather than running words together`() {
        val phrase = PasswordGenerator.generatePassphrase(
            PassphraseOptions(words = 3, separator = "", includeNumber = false),
        )
        assertEquals(3, phrase.split("-").size, phrase)
    }

    @Test
    fun `the word count is clamped to the supported range`() {
        val short = PasswordGenerator.generatePassphrase(
            PassphraseOptions(words = 1, includeNumber = false),
        )
        assertEquals(PasswordGenerator.MIN_WORDS, short.split("-").size)
    }

    @Test
    fun `strength rises with length and variety`() {
        assertEquals(PasswordStrength.VERY_WEAK, PasswordGenerator.strength(""))
        assertEquals(PasswordStrength.VERY_WEAK, PasswordGenerator.strength("abc"))

        val weak = PasswordGenerator.strength("password")
        val fair = PasswordGenerator.strength("Password12")
        val strong = PasswordGenerator.strength("Xk7#pQ2\$mL9!vR4z")
        assertTrue(weak <= fair, "$weak vs $fair")
        assertTrue(fair < strong, "$fair vs $strong")
        assertTrue(strong >= PasswordStrength.STRONG)
    }

    @Test
    fun `a long repetitive password is not treated as strong`() {
        // Length alone must not carry the estimate; "aaaa..." has almost no entropy.
        assertTrue(PasswordGenerator.strength("a".repeat(40)) <= PasswordStrength.WEAK)
    }
}
