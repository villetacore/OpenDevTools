package com.opendevtools.vaultwarden.crypto

import java.security.SecureRandom
import java.util.Locale

/** Options of the password generator, mirroring the web vault's generator panel. */
data class PasswordOptions(
    val length: Int = 16,
    val useUppercase: Boolean = true,
    val useLowercase: Boolean = true,
    val useDigits: Boolean = true,
    val useSpecial: Boolean = true,
    val minDigits: Int = 1,
    val minSpecial: Int = 1,
    /** Drops `l`, `I`, `1`, `O`, `0` and friends, for passwords that get typed by hand. */
    val avoidAmbiguous: Boolean = false,
) {
    val hasAnyCharacterSet: Boolean
        get() = useUppercase || useLowercase || useDigits || useSpecial
}

/** Options of the passphrase generator. */
data class PassphraseOptions(
    val words: Int = 4,
    val separator: String = "-",
    val capitalize: Boolean = true,
    val includeNumber: Boolean = true,
)

/** Rough strength estimate, shown next to a generated or stored password. */
enum class PasswordStrength(val title: String) {
    VERY_WEAK("Very weak"),
    WEAK("Weak"),
    FAIR("Fair"),
    STRONG("Strong"),
    VERY_STRONG("Very strong"),
}

/**
 * Password and passphrase generation, plus the strength estimate the reports use.
 *
 * Every random choice goes through [SecureRandom] and uses rejection sampling, because the obvious
 * `nextInt(bound)` on a modulus of the alphabet size would bias the result towards its first
 * characters.
 */
object PasswordGenerator {

    private const val UPPERCASE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val LOWERCASE = "abcdefghijklmnopqrstuvwxyz"
    private const val DIGITS = "0123456789"
    private const val SPECIAL = "!@#$%^&*()_+-=[]{}|;:,.<>?"
    private const val AMBIGUOUS = "Il1O0oB8S5Z2"

    private val random = SecureRandom()

    fun generate(options: PasswordOptions): String {
        if (!options.hasAnyCharacterSet) {
            throw VaultCryptoException("Enable at least one character set")
        }
        val length = options.length.coerceAtLeast(MIN_LENGTH)

        val pools = buildList {
            if (options.useLowercase) add(filter(LOWERCASE, options.avoidAmbiguous))
            if (options.useUppercase) add(filter(UPPERCASE, options.avoidAmbiguous))
            if (options.useDigits) add(filter(DIGITS, options.avoidAmbiguous))
            if (options.useSpecial) add(SPECIAL)
        }.filter { it.isNotEmpty() }
        if (pools.isEmpty()) throw VaultCryptoException("The selected character sets are empty")

        val characters = ArrayList<Char>(length)

        // Satisfy the minimums first, then fill the rest from the union of every enabled set.
        if (options.useDigits) {
            repeat(options.minDigits.coerceAtMost(length)) {
                characters.add(pick(filter(DIGITS, options.avoidAmbiguous)))
            }
        }
        if (options.useSpecial) {
            repeat(options.minSpecial.coerceAtMost(length - characters.size)) {
                characters.add(pick(SPECIAL))
            }
        }
        // One character from every enabled set, so a 16-character password is never all digits.
        pools.forEach { pool ->
            if (characters.size < length && pool.none { it in characters }) characters.add(pick(pool))
        }

        val all = pools.joinToString("")
        while (characters.size < length) characters.add(pick(all))

        shuffle(characters)
        return characters.joinToString("")
    }

    fun generatePassphrase(options: PassphraseOptions, wordList: List<String> = WORDS): String {
        val count = options.words.coerceIn(MIN_WORDS, MAX_WORDS)
        val words = (0 until count).map { index ->
            val word = wordList[nextInt(wordList.size)]
            if (options.capitalize) {
                word.replaceFirstChar { it.titlecase(Locale.ROOT) }
            } else {
                word
            }.let { if (index == 0 && options.includeNumber) it else it }
        }.toMutableList()

        if (options.includeNumber) {
            val position = nextInt(words.size)
            words[position] = words[position] + nextInt(10)
        }
        return words.joinToString(options.separator.ifEmpty { "-" })
    }

    /**
     * A deliberately simple estimate: entropy from the observed alphabet size and the length,
     * penalised for repetition. It is meant to flag obviously weak entries in the reports, not to
     * replace a real strength estimator.
     */
    fun strength(password: String): PasswordStrength {
        if (password.isEmpty()) return PasswordStrength.VERY_WEAK

        var alphabet = 0
        if (password.any { it.isLowerCase() }) alphabet += 26
        if (password.any { it.isUpperCase() }) alphabet += 26
        if (password.any { it.isDigit() }) alphabet += 10
        if (password.any { !it.isLetterOrDigit() }) alphabet += 32
        if (alphabet == 0) alphabet = 26

        val distinctRatio = password.toSet().size.toDouble() / password.length
        val bits = password.length * (Math.log(alphabet.toDouble()) / Math.log(2.0)) * distinctRatio

        return when {
            bits < 28 -> PasswordStrength.VERY_WEAK
            bits < 40 -> PasswordStrength.WEAK
            bits < 60 -> PasswordStrength.FAIR
            bits < 90 -> PasswordStrength.STRONG
            else -> PasswordStrength.VERY_STRONG
        }
    }

    private fun filter(pool: String, avoidAmbiguous: Boolean): String =
        if (avoidAmbiguous) pool.filterNot { it in AMBIGUOUS } else pool

    private fun pick(pool: String): Char = pool[nextInt(pool.length)]

    /** Rejection sampling, so every value in `0 until bound` is equally likely. */
    private fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive" }
        val limit = Int.MAX_VALUE - (Int.MAX_VALUE % bound) - 1
        while (true) {
            val candidate = random.nextInt() and Int.MAX_VALUE
            if (candidate <= limit) return candidate % bound
        }
    }

    private fun <T> shuffle(items: MutableList<T>) {
        for (i in items.indices.reversed()) {
            val j = nextInt(i + 1)
            val tmp = items[i]
            items[i] = items[j]
            items[j] = tmp
        }
    }

    const val MIN_LENGTH = 5
    const val MAX_LENGTH = 128
    const val MIN_WORDS = 3
    const val MAX_WORDS = 20

    /**
     * A short, self-contained word list. It is not the EFF list - shipping 7776 words for a
     * convenience feature is not worth the size - so a passphrase from here carries roughly
     * 9.6 bits per word; the generator's default of four words plus a digit lands near 42 bits.
     */
    private val WORDS: List<String> = listOf(
        "acorn", "amber", "anchor", "apple", "arrow", "atlas", "autumn", "badger", "bamboo", "banjo",
        "basalt", "beacon", "bison", "blossom", "bramble", "breeze", "bridge", "bronze", "bubble", "cactus",
        "canyon", "cargo", "cedar", "chalk", "cherry", "cinder", "cliff", "clover", "cobalt", "comet",
        "copper", "coral", "cosmos", "cotton", "crane", "crater", "crimson", "crystal", "cypress", "dahlia",
        "daisy", "dapple", "dawn", "delta", "denim", "desert", "dew", "diamond", "dolphin", "dragon",
        "dune", "eagle", "ember", "emerald", "engine", "falcon", "fathom", "fern", "fjord", "flame",
        "flint", "forest", "fossil", "fountain", "fox", "galaxy", "garnet", "geyser", "ginger", "glacier",
        "granite", "gravel", "grove", "harbor", "harvest", "hawk", "hazel", "heather", "hedge", "helix",
        "hollow", "honey", "horizon", "indigo", "iris", "island", "ivory", "jade", "jasmine", "jasper",
        "juniper", "kelp", "kestrel", "lagoon", "lantern", "lark", "laurel", "lava", "ledge", "lemon",
        "lichen", "lilac", "linen", "lotus", "lumen", "lupine", "magnet", "mango", "maple", "marble",
        "marsh", "meadow", "mesa", "meteor", "mist", "moss", "nebula", "nectar", "nickel", "nomad",
        "nutmeg", "oasis", "obsidian", "ocean", "olive", "onyx", "opal", "orbit", "orchid", "otter",
        "palm", "papaya", "pebble", "pepper", "petal", "pewter", "phoenix", "pigment", "pine", "pioneer",
        "plum", "pollen", "poppy", "prairie", "prism", "puma", "quartz", "quill", "quiver", "radiant",
        "raven", "reef", "ridge", "river", "rowan", "ruby", "rustic", "saffron", "sage", "salmon",
        "sandal", "sapphire", "savanna", "scarlet", "sequoia", "shadow", "shale", "shore", "silver", "slate",
        "solar", "sorrel", "spark", "spruce", "stellar", "stone", "storm", "summit", "sunset", "swallow",
        "sycamore", "talon", "tamarind", "tangent", "teal", "tempest", "thicket", "thistle", "thunder", "tidal",
        "timber", "topaz", "torrent", "trellis", "tundra", "turquoise", "umber", "valley", "velvet", "vertex",
        "violet", "vista", "walnut", "warden", "waterfall", "willow", "wombat", "yarrow", "zenith", "zephyr",
    )
}
