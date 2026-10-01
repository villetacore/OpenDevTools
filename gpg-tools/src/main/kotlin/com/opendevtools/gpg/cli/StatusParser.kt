package com.opendevtools.gpg.cli

import com.opendevtools.gpg.model.DecryptionResult
import com.opendevtools.gpg.model.ImportResult
import com.opendevtools.gpg.model.SignatureCheck
import com.opendevtools.gpg.model.SignatureStatus
import com.opendevtools.gpg.model.VerificationResult

/** One `[GNUPG:] KEYWORD arg arg` line. */
data class StatusLine(val keyword: String, val args: List<String>) {

    fun arg(index: Int): String = args.getOrNull(index).orEmpty()

    /** The remainder from [index] on, rejoined - user ids are the last field and contain spaces. */
    fun rest(index: Int): String = args.drop(index).joinToString(" ")
}

/**
 * Reads the `--status-fd` stream, which is how GnuPG reports results to programs.
 *
 * Everything else gpg prints is meant for a person and is translated into their locale, so a
 * plugin that matched on it would work on an English machine and fail silently on any other. The
 * status stream is stable, documented in the `DETAILS` file, and never translated.
 */
object StatusParser {

    private const val PREFIX = "[GNUPG:] "

    fun lines(output: String): List<StatusLine> =
        output.lineSequence()
            .filter { it.startsWith(PREFIX) }
            .mapNotNull { line ->
                val parts = line.removePrefix(PREFIX).trim().split(' ').filter { it.isNotEmpty() }
                parts.firstOrNull()?.let { StatusLine(it, parts.drop(1)) }
            }
            .toList()

    /** True when GnuPG asked for a passphrase it never got - the user dismissed the pinentry. */
    fun wasCancelled(status: List<StatusLine>): Boolean =
        status.any { it.keyword == "MISSING_PASSPHRASE" } ||
            status.any { it.keyword == "FAILURE" && it.args.any { arg -> arg.endsWith("99") } } ||
            status.any { it.keyword == "ERROR" && it.arg(0).contains("cancel", ignoreCase = true) }

    fun verification(output: String, outputPath: String? = null): VerificationResult =
        VerificationResult(signatures(lines(output)), outputPath)

    /**
     * Collects the signatures out of a status stream.
     *
     * One file can carry several, and GnuPG reports each as a run of lines: a result keyword that
     * names the signer, then `VALIDSIG` with the fingerprint and timestamps, then a `TRUST_*`
     * verdict. `NEWSIG` opens each run on modern versions; where it is missing, a second result
     * keyword is taken to open the next one.
     */
    fun signatures(statusLines: List<StatusLine>): List<SignatureCheck> {
        val found = mutableListOf<Builder>()
        var current: Builder? = null

        fun open(): Builder = Builder().also { found += it; current = it }

        // Every assignment below is written `this.status`: a parameter named `status` would
        // shadow the builder's field inside `apply`, and the compiler would be right to complain.
        for (line in statusLines) {
            when (line.keyword) {
                "NEWSIG" -> open()

                "GOODSIG" -> (current?.takeIf { it.status == null } ?: open()).apply {
                    this.status = SignatureStatus.VALID
                    keyId = line.arg(0)
                    signerUid = line.rest(1)
                }

                "BADSIG" -> (current?.takeIf { it.status == null } ?: open()).apply {
                    this.status = SignatureStatus.BAD
                    keyId = line.arg(0)
                    signerUid = line.rest(1)
                }

                "EXPSIG" -> (current?.takeIf { it.status == null } ?: open()).apply {
                    this.status = SignatureStatus.EXPIRED_SIGNATURE
                    keyId = line.arg(0)
                    signerUid = line.rest(1)
                }

                "EXPKEYSIG" -> (current?.takeIf { it.status == null } ?: open()).apply {
                    this.status = SignatureStatus.EXPIRED_KEY
                    keyId = line.arg(0)
                    signerUid = line.rest(1)
                }

                "REVKEYSIG" -> (current?.takeIf { it.status == null } ?: open()).apply {
                    this.status = SignatureStatus.REVOKED_KEY
                    keyId = line.arg(0)
                    signerUid = line.rest(1)
                }

                // ERRSIG <keyid> <pkalgo> <hashalgo> <sigclass> <time> <rc> <fpr>; rc 9 is the
                // only one worth a distinct message, and it means "I do not have that key".
                "ERRSIG" -> (current?.takeIf { it.status == null } ?: open()).apply {
                    val code = line.arg(5)
                    this.status = if (code == NO_PUBKEY_RC) {
                        SignatureStatus.NO_PUBLIC_KEY
                    } else {
                        SignatureStatus.BAD
                    }
                    keyId = line.arg(0)
                    created = line.arg(4).toLongOrNull() ?: 0
                    line.arg(6).takeIf { it.length >= FINGERPRINT_MIN }?.let { fingerprint = it }
                    if (code != NO_PUBKEY_RC) detail = "GnuPG reported error $code"
                }

                // NO_PUBKEY follows the ERRSIG it explains rather than opening a run of its own,
                // so it refines the signature in hand instead of adding a second one.
                "NO_PUBKEY" -> (current ?: open()).apply {
                    if (status == null || status == SignatureStatus.BAD) {
                        this.status = SignatureStatus.NO_PUBLIC_KEY
                    }
                    if (keyId.isEmpty()) keyId = line.arg(0)
                }

                // VALIDSIG <fpr> <date> <sig-ts> <expire-ts> ... [<primary-key-fpr>]
                "VALIDSIG" -> current?.apply {
                    fingerprint = line.arg(0)
                    created = line.arg(2).toLongOrNull() ?: created
                    primaryFingerprint = line.args.lastOrNull()?.takeIf { it.length >= FINGERPRINT_MIN }
                }

                // A good signature from a key nobody vouches for is a different statement from a
                // good signature from a key the user trusts, and Kleopatra draws them differently.
                "TRUST_UNDEFINED", "TRUST_NEVER" -> current?.apply {
                    if (this.status == SignatureStatus.VALID) {
                        this.status = SignatureStatus.VALID_UNTRUSTED
                    }
                }
            }
        }
        return found.filter { it.status != null }.map { it.build() }
    }

    /** Reads the decryption status, including any signature the plaintext also carried. */
    fun decryption(output: String, outputPath: String? = null): DecryptionResult {
        val status = lines(output)
        val okay = status.any { it.keyword == "DECRYPTION_OKAY" }
        val failed = status.any { it.keyword == "DECRYPTION_FAILED" }

        return DecryptionResult(
            success = okay && !failed,
            recipients = status.filter { it.keyword == "ENC_TO" }.map { it.arg(0) },
            // DECRYPTION_KEY <fpr of the subkey> <fpr of its primary key> <ownertrust>
            decryptionKey = status.firstOrNull { it.keyword == "DECRYPTION_KEY" }?.arg(0),
            // A passphrase-encrypted message names no recipient key at all.
            symmetric = status.none { it.keyword == "ENC_TO" } &&
                status.any { it.keyword == "BEGIN_DECRYPTION" || it.keyword == "NEED_PASSPHRASE_SYM" },
            signatures = signatures(status),
            outputPath = outputPath,
            failureReason = when {
                okay && !failed -> ""
                wasCancelled(status) -> "Cancelled at the passphrase prompt"
                status.any { it.keyword == "NO_SECKEY" } ->
                    "The message is encrypted to a key that is not in this keyring"
                else -> "GnuPG could not decrypt the message"
            },
        )
    }

    /**
     * Reads the counters of an import.
     *
     * `IMPORT_RES` has gained and lost a field across GnuPG versions, but only after the fifth:
     * the counts this reads all sit at indices that both layouts agree on.
     */
    fun import(output: String): ImportResult {
        val status = lines(output)
        val fingerprints = status.filter { it.keyword == "IMPORT_OK" }
            .map { it.arg(1) }
            .filter { it.length >= FINGERPRINT_MIN }
            .distinct()

        val counts = status.lastOrNull { it.keyword == "IMPORT_RES" }
            ?: return ImportResult.EMPTY.copy(fingerprints = fingerprints)

        fun count(index: Int): Int = counts.arg(index).toIntOrNull() ?: 0

        return ImportResult(
            considered = count(0),
            imported = count(2),
            unchanged = count(4),
            newUserIds = count(5),
            newSubkeys = count(6),
            newSignatures = count(7),
            secretImported = count(10),
            secretUnchanged = count(11),
            fingerprints = fingerprints,
        )
    }

    /** The fingerprints a `--gen-key` or `--import` run produced, in the order GnuPG reported them. */
    fun touchedFingerprints(output: String): List<String> =
        lines(output)
            .filter { it.keyword == "KEY_CREATED" || it.keyword == "IMPORT_OK" }
            // `KEY_CREATED <type> <fpr>` and `IMPORT_OK <reason> <fpr>` agree on the position.
            .map { it.arg(1) }
            .filter { it.length >= FINGERPRINT_MIN }
            .distinct()

    private class Builder {
        var status: SignatureStatus? = null
        var keyId: String = ""
        var signerUid: String = ""
        var fingerprint: String? = null
        var primaryFingerprint: String? = null
        var created: Long = 0
        var detail: String = ""

        fun build(): SignatureCheck = SignatureCheck(
            status = status ?: SignatureStatus.NONE,
            // The key id is the tail of the fingerprint, so one can stand in for the other.
            keyId = keyId.ifEmpty { fingerprint?.takeLast(KEY_ID_LENGTH).orEmpty() },
            fingerprint = primaryFingerprint ?: fingerprint,
            signerUid = signerUid,
            created = created,
            detail = detail,
        )
    }

    private const val NO_PUBKEY_RC = "9"
    private const val FINGERPRINT_MIN = 32
    private const val KEY_ID_LENGTH = 16
}
