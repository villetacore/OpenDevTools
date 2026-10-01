package com.opendevtools.gpg.model

/** How a signature came out, as read from the `--status-fd` stream rather than from prose. */
enum class SignatureStatus(val title: String) {
    VALID("Valid signature"),
    VALID_UNTRUSTED("Valid signature from an untrusted key"),
    EXPIRED_SIGNATURE("Signature has expired"),
    EXPIRED_KEY("Signed with an expired key"),
    REVOKED_KEY("Signed with a revoked key"),
    BAD("Bad signature - the content does not match"),
    NO_PUBLIC_KEY("Cannot check: the signing key is not in the keyring"),
    NONE("No signature found"),
    ;

    /** Only [VALID] means "this really is from them and the bytes are untouched". */
    val isGood: Boolean get() = this == VALID || this == VALID_UNTRUSTED
}

/** One signature found while verifying; a file can carry several. */
data class SignatureCheck(
    val status: SignatureStatus,
    val keyId: String,
    val fingerprint: String?,
    val signerUid: String,
    val created: Long,
    /** Empty unless GnuPG reported a reason, such as an expired key. */
    val detail: String = "",
) {
    val signerDisplay: String
        get() = signerUid.takeIf { it.isNotBlank() } ?: keyId.takeIf { it.isNotBlank() } ?: "unknown key"
}

/** The result of verifying a signature or a signed file. */
data class VerificationResult(
    val signatures: List<SignatureCheck>,
    /** Where the verified content ended up, for a signature that carried its own payload. */
    val outputPath: String? = null,
) {
    val overall: SignatureStatus
        get() = when {
            signatures.isEmpty() -> SignatureStatus.NONE
            signatures.any { it.status == SignatureStatus.BAD } -> SignatureStatus.BAD
            signatures.all { it.status.isGood } -> signatures.first().status
            else -> signatures.first { !it.status.isGood }.status
        }
}

/** The result of decrypting, including any signature the message also carried. */
data class DecryptionResult(
    val success: Boolean,
    /** Fingerprints of the keys the message was encrypted to, as far as GnuPG could say. */
    val recipients: List<String>,
    /** The key that actually opened it; absent for a passphrase-encrypted message. */
    val decryptionKey: String?,
    val symmetric: Boolean,
    val signatures: List<SignatureCheck>,
    val outputPath: String? = null,
    val failureReason: String = "",
)

/** What `--import` did, as counted by the `IMPORT_RES` status line. */
data class ImportResult(
    val considered: Int,
    val imported: Int,
    val unchanged: Int,
    val newUserIds: Int,
    val newSubkeys: Int,
    val newSignatures: Int,
    val secretImported: Int,
    val secretUnchanged: Int,
    val fingerprints: List<String>,
) {
    /**
     * Reads as a sentence in a balloon. "Unchanged" is worth saying out loud: importing a key you
     * already have looks like a no-op otherwise, and users reasonably wonder whether it worked.
     */
    fun describe(): String = buildList {
        if (imported > 0) add("$imported new certificate(s)")
        if (secretImported > 0) add("$secretImported secret key(s)")
        if (unchanged > 0 || secretUnchanged > 0) add("${unchanged + secretUnchanged} already present")
        if (newUserIds > 0) add("$newUserIds new user id(s)")
        if (newSubkeys > 0) add("$newSubkeys new subkey(s)")
        if (newSignatures > 0) add("$newSignatures new signature(s)")
    }.ifEmpty { listOf("nothing to import") }.joinToString(", ")

    companion object {
        val EMPTY = ImportResult(0, 0, 0, 0, 0, 0, 0, 0, emptyList())
    }
}

/** A hit from `--search-keys`, which is not a certificate yet - it has not been imported. */
data class KeyserverHit(
    val keyId: String,
    val algorithm: Int,
    val bits: Int,
    val created: Long,
    val expires: Long,
    val revoked: Boolean,
    val userIds: List<String>,
) {
    val display: String get() = userIds.firstOrNull() ?: keyId

    val algorithmTitle: String get() = PublicKeyAlgorithm.describe(algorithm, bits, null)
}

/** Which key material an export should contain. */
enum class ExportKind(val title: String) {
    PUBLIC("Certificate (public key)"),
    SECRET("Secret key"),
    SECRET_SUBKEYS("Secret subkeys only"),
    SSH("SSH public key"),
}

/**
 * The algorithms offered by the key generation dialog.
 *
 * The strings are the algorithm names `--quick-generate-key` accepts. Pairing each with the
 * "default" usage is what makes GnuPG build the usual two-key certificate - a certify/sign primary
 * with an encryption subkey - rather than a single key that cannot receive mail.
 */
enum class KeyAlgorithm(val spec: String, val title: String) {
    ED25519("ed25519", "ECC - Curve 25519 (recommended)"),
    NIST_P256("nistp256", "ECC - NIST P-256"),
    NIST_P384("nistp384", "ECC - NIST P-384"),
    NIST_P521("nistp521", "ECC - NIST P-521"),
    BRAINPOOL_P256("brainpoolP256r1", "ECC - Brainpool P-256"),
    BRAINPOOL_P384("brainpoolP384r1", "ECC - Brainpool P-384"),
    RSA_2048("rsa2048", "RSA 2048"),
    RSA_3072("rsa3072", "RSA 3072"),
    RSA_4096("rsa4096", "RSA 4096"),
    FUTURE_DEFAULT("future-default", "Whatever GnuPG will default to next"),
}

/** Everything the generate-key dialog collects. */
data class KeyGenSpec(
    val name: String,
    val email: String,
    val comment: String,
    val algorithm: KeyAlgorithm,
    /** A GnuPG expiry expression: `0` for never, or `2y`, `18m`, `90d`, or an ISO date. */
    val expiry: String,
) {
    /** The `Name (comment) <mail>` form GnuPG parses back into a user id. */
    val userId: String
        get() = buildString {
            append(name.trim())
            comment.trim().takeIf { it.isNotEmpty() }?.let { append(" (").append(it).append(")") }
            email.trim().takeIf { it.isNotEmpty() }?.let { append(" <").append(it).append(">") }
        }
}

/** How long from now something should stay valid; shared by key generation and expiry changes. */
enum class ExpiryPreset(val expression: String, val title: String) {
    ONE_YEAR("1y", "1 year"),
    TWO_YEARS("2y", "2 years"),
    THREE_YEARS("3y", "3 years"),
    NEVER("0", "Never expires"),
    CUSTOM("", "Custom..."),
}
