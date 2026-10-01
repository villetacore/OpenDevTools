package com.opendevtools.gpg.model

/**
 * How much this keyring believes a key really belongs to the person named in its user id.
 *
 * The single-letter codes are field 2 of every `--with-colons` record. GnuPG spells "unknown" two
 * ways - `-` for "no value" and `o` for "new to us" - and both mean the same thing to a reader.
 */
enum class Validity(val code: Char, val title: String) {
    UNKNOWN('-', "Unknown"),
    INVALID('i', "Invalid"),
    DISABLED('d', "Disabled"),
    REVOKED('r', "Revoked"),
    EXPIRED('e', "Expired"),
    UNDEFINED('q', "Undefined"),
    NEVER('n', "Not trusted"),
    MARGINAL('m', "Marginal"),
    FULL('f', "Full"),
    ULTIMATE('u', "Ultimate"),
    PRIVATE_PART('w', "Own key"),
    SPECIAL('s', "Special");

    /** True where the key can still be used; anything else the UI paints as a problem. */
    val isUsable: Boolean
        get() = this != REVOKED && this != EXPIRED && this != INVALID && this != DISABLED

    companion object {
        fun of(code: String?): Validity {
            val first = code?.trim()?.firstOrNull() ?: return UNKNOWN
            if (first == 'o') return UNKNOWN
            return entries.firstOrNull { it.code == first } ?: UNKNOWN
        }
    }
}

/** What a key or subkey is allowed to do; field 12 of a `pub`, `sub`, `sec` or `ssb` record. */
enum class KeyCapability(val code: Char, val title: String) {
    ENCRYPT('e', "Encrypt"),
    SIGN('s', "Sign"),
    CERTIFY('c', "Certify"),
    AUTHENTICATE('a', "Authenticate"),
    RESTRICTED_ENCRYPT('r', "Restricted encryption"),
    TIMESTAMP('t', "Timestamp"),
    GROUP('g', "Group");

    companion object {
        /**
         * What this one key can do. GnuPG also repeats each letter in upper case on the primary
         * key to mean "somewhere in this certificate", so only the lower-case letters describe the
         * record they appear on.
         */
        fun parseOwn(field: String?): Set<KeyCapability> =
            field.orEmpty().filter { it.isLowerCase() }
                .mapNotNullTo(LinkedHashSet()) { c -> entries.firstOrNull { it.code == c } }

        /** The union over the primary key and all of its subkeys, as the upper-case letters say. */
        fun parseWhole(field: String?): Set<KeyCapability> =
            field.orEmpty().filter { it.isUpperCase() }
                .mapNotNullTo(LinkedHashSet()) { c ->
                    entries.firstOrNull { it.code == c.lowercaseChar() }
                }

        fun describe(capabilities: Set<KeyCapability>): String =
            if (capabilities.isEmpty()) "-" else capabilities.joinToString(", ") { it.title }
    }
}

/**
 * Owner trust as `--import-ownertrust` writes it.
 *
 * The numbers are that file's numbers, which are deliberately *not* the ones the interactive
 * `trust` menu shows (1..5 there, 2..6 here). Feeding a trust file is the only way to set this
 * without driving `--edit-key` through a pseudo-terminal.
 */
enum class OwnerTrust(val fileValue: Int, val title: String, val hint: String) {
    UNKNOWN(2, "Unknown", "No opinion; the default for an imported key"),
    NEVER(3, "Never", "Never trust this key to certify other keys"),
    MARGINAL(4, "Marginal", "Trust it partially; three marginals make a full chain"),
    FULL(5, "Full", "Trust it to certify other keys correctly"),
    ULTIMATE(6, "Ultimate", "Treat it as your own key; it certifies without limit");

    companion object {
        /** Field 9 of a `pub` record carries the same letters the validity field uses. */
        fun of(code: String?): OwnerTrust = when (Validity.of(code)) {
            Validity.NEVER -> NEVER
            Validity.MARGINAL -> MARGINAL
            Validity.FULL -> FULL
            Validity.ULTIMATE -> ULTIMATE
            else -> UNKNOWN
        }
    }
}

/** Public key algorithm numbers, as assigned by RFC 4880 and RFC 9580. */
object PublicKeyAlgorithm {

    private val NAMES = mapOf(
        1 to "RSA",
        2 to "RSA (encrypt only)",
        3 to "RSA (sign only)",
        16 to "ElGamal",
        17 to "DSA",
        18 to "ECDH",
        19 to "ECDSA",
        20 to "ElGamal",
        22 to "EdDSA",
        25 to "X25519",
        26 to "X448",
        27 to "Ed25519",
        28 to "Ed448",
    )

    /** The curve name is more informative than the bit count wherever GnuPG supplies one. */
    fun describe(algorithm: Int, bits: Int, curve: String?): String {
        val name = NAMES[algorithm] ?: "algorithm $algorithm"
        return when {
            !curve.isNullOrBlank() -> "$name ($curve)"
            bits > 0 -> "$name $bits"
            else -> name
        }
    }
}

/** A certification made on a user id by some key. */
data class GpgSignature(
    val keyId: String,
    val signerUid: String,
    val created: Long,
    val expires: Long,
    /** `10`..`13` certify a user id, `18`/`19` bind a subkey, `30` revokes a certification. */
    val signatureClass: String,
    val exportable: Boolean,
    val issuerFingerprint: String,
    val revocation: Boolean,
    /** Made by the certificate that carries it, which every key has and nobody needs to see. */
    val selfSignature: Boolean,
) {
    val classTitle: String
        get() = when (signatureClass.take(2)) {
            "10" -> "Generic certification"
            "11" -> "Persona certification"
            "12" -> "Casual certification"
            "13" -> "Positive certification"
            "18" -> "Subkey binding"
            "19" -> "Primary key binding"
            "30" -> "Certification revocation"
            else -> "Class $signatureClass"
        }

    /** What the signer called themselves; GnuPG leaves it empty for a key we do not have. */
    val signerDisplay: String get() = signerUid.takeIf { it.isNotBlank() } ?: "(key $keyId not in keyring)"
}

/** One identity claimed by a key: a name, an optional comment, and usually a mail address. */
data class GpgUserId(
    val uid: String,
    val validity: Validity,
    val created: Long,
    val hash: String,
    val signatures: List<GpgSignature> = emptyList(),
    /** A photo id rather than a text one; GnuPG reports these as `uat` records. */
    val isAttribute: Boolean = false,
) {
    val revoked: Boolean get() = validity == Validity.REVOKED

    /** `Name (comment) <mail@example.org>` split into its parts, best effort. */
    val email: String?
        get() = uid.substringAfter('<', "").substringBefore('>', "").takeIf { it.isNotBlank() }

    val name: String
        get() = uid.substringBefore('<').substringBefore('(').trim().ifBlank { uid }

    val comment: String?
        get() = uid.substringAfter('(', "").substringBefore(')', "").takeIf { it.isNotBlank() }

    val display: String get() = if (isAttribute) "(photo id)" else uid

    /** Certifications by other people; the key's own binding signatures are noise here. */
    val certifications: List<GpgSignature>
        get() = signatures.filter { !it.selfSignature && !it.revocation }
}

/** A subkey; in practice this is where encryption and day-to-day signing actually live. */
data class GpgSubkey(
    val keyId: String,
    val fingerprint: String,
    val algorithm: Int,
    val bits: Int,
    val curve: String?,
    val created: Long,
    val expires: Long,
    val validity: Validity,
    val capabilities: Set<KeyCapability>,
    val hasSecret: Boolean,
    val cardSerial: String?,
) {
    val algorithmTitle: String get() = PublicKeyAlgorithm.describe(algorithm, bits, curve)

    val shortKeyId: String get() = keyId.takeLast(SHORT_KEY_ID_LENGTH)

    val isExpired: Boolean get() = expires in 1 until nowSeconds()

    val isRevoked: Boolean get() = validity == Validity.REVOKED

    val isUsable: Boolean get() = !isExpired && !isRevoked && validity.isUsable
}

/**
 * A certificate as Kleopatra shows one: a primary key, its identities and its subkeys.
 *
 * [hasSecret] is what separates "my own certificate" from "someone else's", and it is the most
 * consequential flag in the model: it decides what can sign and what can decrypt.
 */
data class GpgKey(
    val keyId: String,
    val fingerprint: String,
    val algorithm: Int,
    val bits: Int,
    val curve: String?,
    val created: Long,
    val expires: Long,
    val validity: Validity,
    val ownerTrust: OwnerTrust,
    /** What this primary key itself can do. */
    val capabilities: Set<KeyCapability>,
    /** What the certificate as a whole can do, subkeys included; this is what the UI filters on. */
    val wholeCapabilities: Set<KeyCapability>,
    val userIds: List<GpgUserId>,
    val subkeys: List<GpgSubkey>,
    val hasSecret: Boolean,
    /** The secret key lives on a smartcard rather than in the private keyring. */
    val onCard: Boolean,
    val disabled: Boolean,
) {
    val primaryUserId: GpgUserId?
        get() = userIds.firstOrNull { !it.isAttribute } ?: userIds.firstOrNull()

    val displayName: String get() = primaryUserId?.display ?: shortKeyId

    val email: String? get() = userIds.firstNotNullOfOrNull { it.email }

    val shortKeyId: String get() = keyId.takeLast(SHORT_KEY_ID_LENGTH)

    val algorithmTitle: String get() = PublicKeyAlgorithm.describe(algorithm, bits, curve)

    val isRevoked: Boolean get() = validity == Validity.REVOKED

    val isExpired: Boolean get() = validity == Validity.EXPIRED || expires in 1 until nowSeconds()

    /** True when a subkey, or the primary key itself, can still encrypt to this certificate. */
    val canEncrypt: Boolean
        get() = !isRevoked && !isExpired && !disabled &&
            (
                KeyCapability.ENCRYPT in capabilities ||
                    subkeys.any { it.isUsable && KeyCapability.ENCRYPT in it.capabilities }
                )

    /** True when this certificate holds a secret key that is still allowed to sign. */
    val canSign: Boolean
        get() = hasSecret && !isRevoked && !isExpired && !disabled &&
            (
                KeyCapability.SIGN in capabilities ||
                    subkeys.any { it.isUsable && KeyCapability.SIGN in it.capabilities }
                )

    /** True when this certificate can certify someone else's, which is what signing a key needs. */
    val canCertify: Boolean
        get() = hasSecret && !isRevoked && !isExpired && !disabled &&
            KeyCapability.CERTIFY in capabilities

    /** The fingerprint in the four-character groups every OpenPGP tool prints. */
    val readableFingerprint: String
        get() = fingerprint.chunked(FINGERPRINT_GROUP).joinToString(" ")

    /** Matches the certificate against the filter box: any user id, the key id or the fingerprint. */
    fun matches(query: String): Boolean {
        val trimmed = query.trim().lowercase()
        if (trimmed.isEmpty()) return true
        // A pasted fingerprint usually arrives in spaced groups; the stored one never has spaces.
        val compact = trimmed.replace(" ", "")
        return fingerprint.lowercase().contains(compact) ||
            keyId.lowercase().contains(compact) ||
            userIds.any { it.uid.lowercase().contains(trimmed) }
    }

    companion object {
        const val FINGERPRINT_GROUP = 4
    }
}

/** Both keyrings folded into one list, which is what the tool window shows. */
data class GpgKeyring(
    val keys: List<GpgKey>,
    val loadedAt: Long = System.currentTimeMillis(),
) {
    val secretKeys: List<GpgKey> get() = keys.filter { it.hasSecret }

    fun byFingerprint(fingerprint: String): GpgKey? = keys.firstOrNull { it.fingerprint == fingerprint }

    companion object {
        val EMPTY = GpgKeyring(emptyList())
    }
}

internal const val SHORT_KEY_ID_LENGTH = 8

internal fun nowSeconds(): Long = System.currentTimeMillis() / 1000
