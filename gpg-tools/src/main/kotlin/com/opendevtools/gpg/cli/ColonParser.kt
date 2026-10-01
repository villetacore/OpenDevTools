package com.opendevtools.gpg.cli

import com.opendevtools.gpg.model.GpgKey
import com.opendevtools.gpg.model.GpgSignature
import com.opendevtools.gpg.model.GpgSubkey
import com.opendevtools.gpg.model.GpgUserId
import com.opendevtools.gpg.model.KeyCapability
import com.opendevtools.gpg.model.KeyserverHit
import com.opendevtools.gpg.model.OwnerTrust
import com.opendevtools.gpg.model.Validity

/**
 * Parses the `--with-colons` listings of GnuPG.
 *
 * This is the only machine-readable output GnuPG offers, and it is the only output this plugin
 * reads: everything gpg writes for humans is translated into the user's locale, so matching on it
 * would work in English and quietly fail everywhere else.
 *
 * The format is one record per line, fields separated by colons, documented in the `DETAILS` file
 * that ships with GnuPG. Records are positional and stateful - an `fpr` line belongs to whichever
 * key line came before it - so this is a fold over the lines rather than a per-line mapping.
 */
object ColonParser {

    // Field numbers are 1-based in the GnuPG documentation; these are the 0-based indices.
    private const val TYPE = 0
    private const val VALIDITY = 1
    private const val LENGTH = 2
    private const val ALGORITHM = 3
    private const val KEY_ID = 4
    private const val CREATED = 5
    private const val EXPIRES = 6
    private const val HASH_OR_SERIAL = 7
    private const val OWNER_TRUST = 8
    private const val USER_ID = 9
    private const val SIGNATURE_CLASS = 10
    private const val CAPABILITIES = 11
    private const val ISSUER = 12
    private const val TOKEN_SERIAL = 14
    private const val CURVE = 16

    /**
     * Folds one listing into certificates.
     *
     * [fromSecretKeyring] says which listing this is: `--list-secret-keys` emits `sec`/`ssb` where
     * `--list-keys` emits `pub`/`sub`, and only the secret listing can tell whether the private
     * half is actually present.
     */
    fun parseKeys(listing: String, fromSecretKeyring: Boolean = false): List<GpgKey> {
        val keys = mutableListOf<KeyBuilder>()
        var key: KeyBuilder? = null
        // Which record an `fpr` or a `sig` line attaches to; both are written after their owner.
        var subkey: SubkeyBuilder? = null
        var userId: UserIdBuilder? = null

        for (line in listing.lineSequence()) {
            val fields = split(line) ?: continue
            when (fields[TYPE]) {
                "pub", "sec" -> {
                    key = KeyBuilder(fields, fromSecretKeyring)
                    keys += key
                    subkey = null
                    userId = null
                }

                "sub", "ssb" -> {
                    if (key == null) continue
                    subkey = SubkeyBuilder(fields, fromSecretKeyring)
                    key.subkeys += subkey
                    // A subkey ends the previous user id; later `sig` lines bind the subkey.
                    userId = null
                }

                "uid", "uat" -> {
                    if (key == null) continue
                    userId = UserIdBuilder(fields, isAttribute = fields[TYPE] == "uat")
                    key.userIds += userId
                    subkey = null
                }

                // The fingerprint of whichever key or subkey was named last.
                "fpr" -> {
                    val value = fields.getOrNull(USER_ID).orEmpty()
                    when {
                        subkey != null -> subkey.fingerprint = value
                        key != null && key.fingerprint.isEmpty() -> key.fingerprint = value
                    }
                }

                "sig", "rev" -> {
                    val signature = signature(fields, key?.fingerprint.orEmpty())
                    // A signature under a subkey is its binding signature and belongs to nobody
                    // the UI shows, so only the ones under a user id are kept.
                    userId?.signatures?.add(signature)
                }

                // `grp` (keygrip), `rvk` (designated revoker), `tru`, `cfg`, `spk` carry nothing
                // this plugin shows, and are skipped rather than mapped to empty rows.
            }
        }
        return keys.map { it.build() }
    }

    /**
     * Folds the public and the secret listing into one list.
     *
     * Every secret key also appears in the public keyring, so the public listing is the spine and
     * the secret one only contributes the "I hold the private half" flags. Doing it the other way
     * round would silently drop every certificate the user does not own.
     */
    fun merge(public: List<GpgKey>, secret: List<GpgKey>): List<GpgKey> {
        if (secret.isEmpty()) return public
        val secretByFingerprint = secret.associateBy { it.fingerprint }
        val secretSubkeys = secret.flatMap { it.subkeys }.associateBy { it.fingerprint }

        return public.map { key ->
            val own = secretByFingerprint[key.fingerprint] ?: return@map key
            key.copy(
                hasSecret = true,
                onCard = own.onCard,
                subkeys = key.subkeys.map { sub ->
                    val ownSub = secretSubkeys[sub.fingerprint]
                    if (ownSub == null) sub
                    else sub.copy(hasSecret = ownSub.hasSecret, cardSerial = ownSub.cardSerial)
                },
            )
        }
    }

    /** Parses the `--search-keys` listing, which uses `pub`/`uid` records of its own shape. */
    fun parseSearchResults(listing: String): List<KeyserverHit> {
        val hits = mutableListOf<KeyserverHit>()
        var current: MutableList<String>? = null

        for (line in listing.lineSequence()) {
            val fields = split(line) ?: continue
            when (fields[TYPE]) {
                // pub:<keyid>:<algo>:<keylen>:<creationdate>:<expirationdate>:<flags>
                "pub" -> {
                    val flags = fields.getOrNull(6).orEmpty()
                    val userIds = mutableListOf<String>()
                    current = userIds
                    hits += KeyserverHit(
                        keyId = fields.getOrNull(1).orEmpty(),
                        algorithm = fields.getOrNull(2)?.toIntOrNull() ?: 0,
                        bits = fields.getOrNull(3)?.toIntOrNull() ?: 0,
                        created = timestamp(fields.getOrNull(4)),
                        expires = timestamp(fields.getOrNull(5)),
                        revoked = flags.contains('r', ignoreCase = true),
                        userIds = userIds,
                    )
                }

                // uid:<escaped uid>:<creationdate>:<expirationdate>:<flags>
                "uid" -> current?.add(unescape(fields.getOrNull(1).orEmpty()))
            }
        }
        return hits.filter { it.userIds.isNotEmpty() || it.keyId.isNotEmpty() }
    }

    private fun signature(fields: List<String>, ownerFingerprint: String): GpgSignature {
        val issuer = fields.getOrNull(ISSUER).orEmpty()
        val rawClass = fields.getOrNull(SIGNATURE_CLASS).orEmpty()
        return GpgSignature(
            keyId = fields.getOrNull(KEY_ID).orEmpty(),
            signerUid = unescape(fields.getOrNull(USER_ID).orEmpty()),
            created = timestamp(fields.getOrNull(CREATED)),
            expires = timestamp(fields.getOrNull(EXPIRES)),
            // The trailing letter is the exportability flag, not part of the class number.
            signatureClass = rawClass.takeWhile { it.isDigit() },
            exportable = !rawClass.endsWith("l"),
            issuerFingerprint = issuer,
            revocation = fields[TYPE] == "rev" || rawClass.startsWith("30"),
            // GnuPG only fills the issuer fingerprint on newer signatures, so fall back to the
            // key id, which is the low 64 bits of that same fingerprint.
            selfSignature = when {
                issuer.isNotEmpty() -> issuer == ownerFingerprint
                else -> ownerFingerprint.endsWith(fields.getOrNull(KEY_ID).orEmpty())
            },
        )
    }

    /**
     * Splits one record, or returns null for a line that is not one.
     *
     * A listing can start with warnings on stdout in some configurations, and always ends with a
     * blank line, so anything without the record shape is dropped rather than half-parsed.
     */
    private fun split(line: String): List<String>? {
        if (line.isBlank() || !line.contains(':')) return null
        val fields = line.split(':')
        return fields.takeIf { it[TYPE].isNotEmpty() && it.size > VALIDITY }
    }

    /**
     * Reads a GnuPG date field, which is an epoch second count on most builds but an ISO
     * `yyyymmddThhmmss` stamp on some, and empty wherever the value does not apply.
     */
    internal fun timestamp(value: String?): Long {
        val text = value?.trim().orEmpty()
        if (text.isEmpty() || text == "0") return 0
        text.toLongOrNull()?.let { return it }

        val iso = Regex("""^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})$""").find(text) ?: return 0
        val (year, month, day, hour, minute, second) = iso.destructured
        return runCatching {
            java.time.LocalDateTime.of(
                year.toInt(), month.toInt(), day.toInt(), hour.toInt(), minute.toInt(), second.toInt(),
            ).toEpochSecond(java.time.ZoneOffset.UTC)
        }.getOrDefault(0)
    }

    /**
     * Undoes the escaping of the user-id field, where GnuPG writes any colon, backslash or
     * control character as `\xNN`. Leaving this out would truncate every user id containing a
     * colon at the colon.
     */
    internal fun unescape(value: String): String {
        if (!value.contains("\\x")) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val char = value[i]
            if (char == '\\' && i + 3 < value.length && value[i + 1] == 'x') {
                val code = value.substring(i + 2, i + 4).toIntOrNull(16)
                if (code != null) {
                    out.append(code.toChar())
                    i += 4
                    continue
                }
            }
            out.append(char)
            i++
        }
        return out.toString()
    }

    private class KeyBuilder(private val fields: List<String>, private val secretListing: Boolean) {
        var fingerprint: String = ""
        val userIds = mutableListOf<UserIdBuilder>()
        val subkeys = mutableListOf<SubkeyBuilder>()

        fun build(): GpgKey {
            val capabilities = fields.getOrNull(CAPABILITIES).orEmpty()
            val token = fields.getOrNull(TOKEN_SERIAL).orEmpty()
            return GpgKey(
                keyId = fields.getOrNull(KEY_ID).orEmpty(),
                fingerprint = fingerprint,
                algorithm = fields.getOrNull(ALGORITHM)?.toIntOrNull() ?: 0,
                bits = fields.getOrNull(LENGTH)?.toIntOrNull() ?: 0,
                curve = fields.getOrNull(CURVE)?.takeIf { it.isNotBlank() },
                created = timestamp(fields.getOrNull(CREATED)),
                expires = timestamp(fields.getOrNull(EXPIRES)),
                validity = Validity.of(fields.getOrNull(VALIDITY)),
                ownerTrust = OwnerTrust.of(fields.getOrNull(OWNER_TRUST)),
                capabilities = KeyCapability.parseOwn(capabilities),
                wholeCapabilities = KeyCapability.parseWhole(capabilities).ifEmpty {
                    KeyCapability.parseOwn(capabilities)
                },
                userIds = userIds.map { it.build() },
                subkeys = subkeys.map { it.build() },
                // The secret listing shows a key only when its private half exists somewhere;
                // the public listing can never answer this.
                hasSecret = secretListing && token != STUB,
                onCard = secretListing && isCardToken(token),
                // `D` in the capability field is how GnuPG reports a disabled key.
                disabled = capabilities.contains('D'),
            )
        }
    }

    private class SubkeyBuilder(private val fields: List<String>, private val secretListing: Boolean) {
        var fingerprint: String = ""

        fun build(): GpgSubkey {
            val token = fields.getOrNull(TOKEN_SERIAL).orEmpty()
            return GpgSubkey(
                keyId = fields.getOrNull(KEY_ID).orEmpty(),
                fingerprint = fingerprint,
                algorithm = fields.getOrNull(ALGORITHM)?.toIntOrNull() ?: 0,
                bits = fields.getOrNull(LENGTH)?.toIntOrNull() ?: 0,
                curve = fields.getOrNull(CURVE)?.takeIf { it.isNotBlank() },
                created = timestamp(fields.getOrNull(CREATED)),
                expires = timestamp(fields.getOrNull(EXPIRES)),
                validity = Validity.of(fields.getOrNull(VALIDITY)),
                capabilities = KeyCapability.parseOwn(fields.getOrNull(CAPABILITIES)),
                // Field 15 is empty or `+` for a key held in the keyring, `#` for a stub with no
                // key material at all, and the token's serial number for one held on a smartcard.
                // Treating anything but `+` as "no secret" would tell a card user their own key
                // cannot sign.
                hasSecret = secretListing && token != STUB,
                cardSerial = token.takeIf { isCardToken(it) },
            )
        }
    }

    /** A stub: GnuPG knows the key exists but holds none of its private material. */
    private const val STUB = "#"

    /**
     * Field 15 names a smartcard when it is neither empty, nor `+`, nor a stub - what is left is
     * the token's serial number. Some versions write `>` there instead, so that counts too.
     */
    private fun isCardToken(token: String): Boolean =
        token.isNotEmpty() && token != "+" && token != STUB

    private class UserIdBuilder(private val fields: List<String>, private val isAttribute: Boolean) {
        val signatures = mutableListOf<GpgSignature>()

        fun build(): GpgUserId = GpgUserId(
            // A `uat` record has no text; field 10 holds the attribute's byte count instead.
            uid = if (isAttribute) "" else unescape(fields.getOrNull(USER_ID).orEmpty()),
            validity = Validity.of(fields.getOrNull(VALIDITY)),
            created = timestamp(fields.getOrNull(CREATED)),
            hash = fields.getOrNull(HASH_OR_SERIAL).orEmpty(),
            signatures = signatures.toList(),
            isAttribute = isAttribute,
        )
    }
}
