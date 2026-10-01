package com.opendevtools.gpg.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.application.ApplicationManager
import com.opendevtools.gpg.cli.ColonParser
import com.opendevtools.gpg.cli.GpgException
import com.opendevtools.gpg.cli.GpgProcess
import com.opendevtools.gpg.cli.StatusParser
import com.opendevtools.gpg.model.DecryptionResult
import com.opendevtools.gpg.model.ExportKind
import com.opendevtools.gpg.model.GpgKey
import com.opendevtools.gpg.model.GpgKeyring
import com.opendevtools.gpg.model.ImportResult
import com.opendevtools.gpg.model.KeyGenSpec
import com.opendevtools.gpg.model.KeyserverHit
import com.opendevtools.gpg.model.OwnerTrust
import com.opendevtools.gpg.model.VerificationResult
import com.opendevtools.gpg.settings.GpgSettings
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Every gpg operation the plugin offers.
 *
 * All methods block and must be called from a background thread. The two rules that shape this
 * class: the only output ever parsed is `--with-colons` and `--status-fd`, because everything else
 * gpg prints is translated into the user's locale; and no method ever handles a passphrase, because
 * gpg-agent asks for those itself.
 */
@Service(Service.Level.APP)
class GpgService {

    @Volatile
    private var cached: GpgKeyring? = null

    fun invalidate() {
        cached = null
    }

    fun cachedKeyring(): GpgKeyring? = cached

    private fun process(): GpgProcess {
        val settings = GpgSettings.getInstance()
        val executable = settings.resolveExecutable()
            ?: throw GpgException(
                "GnuPG was not found. Install it, or set the path in Settings | Tools | GPG.",
            )
        return GpgProcess(executable, settings.homeOrNull())
    }

    fun version(): String? = process().version()

    // --- Listing ---------------------------------------------------------------------------------

    /**
     * Reads both keyrings and folds them into one list.
     *
     * Two calls rather than one `--list-keys --with-secret`: the secret listing is the only place
     * that distinguishes a key held in the keyring from one held on a smartcard, and getting that
     * wrong would leave a card user unable to see why signing fails.
     */
    fun loadKeyring(withSignatures: Boolean = GpgSettings.getInstance().loadSignatures): GpgKeyring {
        val gpg = process()
        val listCommand = if (withSignatures) "--list-sigs" else "--list-keys"

        // An empty keyring is not an error, but several gpg versions exit non-zero for one, so a
        // failure only counts when nothing came back on stdout either. Treating the exit code
        // alone as the answer would greet every fresh install with an error instead of a hint.
        val publicListing = gpg.run(GpgProcess.BATCH + LIST_ARGUMENTS + listOf(listCommand))
        if (!publicListing.isSuccess && publicListing.stdout.isBlank()) {
            throw GpgException(publicListing.errorMessage(), publicListing.exitCode)
        }
        val secret = gpg.run(GpgProcess.BATCH + LIST_ARGUMENTS + listOf("--list-secret-keys")).stdout

        val keys = ColonParser.merge(
            ColonParser.parseKeys(publicListing.stdout, fromSecretKeyring = false),
            ColonParser.parseKeys(secret, fromSecretKeyring = true),
        ).sortedWith(compareByDescending<GpgKey> { it.hasSecret }.thenBy { it.displayName.lowercase() })

        return GpgKeyring(keys).also { cached = it }
    }

    /** Re-reads one certificate, which is what an edit needs rather than a whole refresh. */
    fun reloadKey(fingerprint: String): GpgKey? {
        val gpg = process()
        val publicListing = gpg.run(
            GpgProcess.BATCH + LIST_ARGUMENTS + listOf("--list-keys", fingerprint),
        ).stdout
        val secret = gpg.run(
            GpgProcess.BATCH + LIST_ARGUMENTS + listOf("--list-secret-keys", fingerprint),
        ).stdout
        return ColonParser.merge(
            ColonParser.parseKeys(publicListing, fromSecretKeyring = false),
            ColonParser.parseKeys(secret, fromSecretKeyring = true),
        ).firstOrNull()
    }

    // --- Import and export -----------------------------------------------------------------------

    fun importFiles(paths: List<String>): ImportResult {
        val result = process().run(GpgProcess.BATCH + listOf("--import") + paths)
        // An import that brought nothing new still exits non-zero on some versions; the counters
        // in the status stream are the truth, so the exit code alone must not decide.
        val counts = StatusParser.import(result.stderr)
        if (!result.isSuccess && counts.fingerprints.isEmpty() && counts.considered == 0) {
            throw GpgException(result.errorMessage(), result.exitCode)
        }
        invalidate()
        return counts
    }

    fun importText(armored: String): ImportResult {
        val result = process().run(
            GpgProcess.BATCH + listOf("--import"),
            stdin = armored.toByteArray(StandardCharsets.UTF_8),
        )
        val counts = StatusParser.import(result.stderr)
        if (!result.isSuccess && counts.fingerprints.isEmpty() && counts.considered == 0) {
            throw GpgException(result.errorMessage(), result.exitCode)
        }
        invalidate()
        return counts
    }

    /** Exports as text, for the clipboard and for the "show me the key" dialog. */
    fun exportArmored(fingerprints: List<String>, kind: ExportKind): String {
        val arguments = when (kind) {
            ExportKind.PUBLIC -> listOf("--armor", "--export")
            ExportKind.SECRET -> listOf("--armor", "--export-secret-keys")
            ExportKind.SECRET_SUBKEYS -> listOf("--armor", "--export-secret-subkeys")
            ExportKind.SSH -> listOf("--export-ssh-key")
        }
        val timeout = if (kind == ExportKind.PUBLIC || kind == ExportKind.SSH) {
            GpgProcess.DEFAULT_TIMEOUT_MS
        } else {
            // Exporting a secret key unlocks it, which means a pinentry dialog and a person.
            GpgProcess.INTERACTIVE_TIMEOUT_MS
        }
        val result = process().require(
            GpgProcess.BATCH + arguments + fingerprints,
            timeoutMs = timeout,
        )
        return result.stdout.trim().ifEmpty {
            throw GpgException("gpg exported nothing for the selected certificate")
        }
    }

    /** Exports straight to a file, which is the only safe route for a binary export. */
    fun exportToFile(fingerprints: List<String>, kind: ExportKind, target: File, armor: Boolean) {
        val command = when (kind) {
            ExportKind.PUBLIC -> "--export"
            ExportKind.SECRET -> "--export-secret-keys"
            ExportKind.SECRET_SUBKEYS -> "--export-secret-subkeys"
            ExportKind.SSH -> "--export-ssh-key"
        }
        val arguments = buildList {
            addAll(GpgProcess.BATCH)
            if (armor && kind != ExportKind.SSH) add("--armor")
            add("--output")
            add(target.absolutePath)
            add(command)
            addAll(fingerprints)
        }
        process().require(arguments, timeoutMs = GpgProcess.INTERACTIVE_TIMEOUT_MS)
    }

    // --- Key management --------------------------------------------------------------------------

    /**
     * Creates a certificate.
     *
     * `--batch` is deliberately absent: under it, and with no `--passphrase` given, GnuPG writes an
     * unprotected private key instead of asking for one. Leaving batch mode off makes gpg-agent
     * raise its pinentry, which is the only way this plugin can get a passphrase onto a new key
     * without ever seeing it.
     */
    fun generateKey(spec: KeyGenSpec): List<String> {
        val result = process().require(
            listOf(
                "--no-tty",
                "--pinentry-mode", "ask",
                "--quick-generate-key",
                spec.userId,
                spec.algorithm.spec,
                // "default" is what makes gpg build the usual pair - a certify/sign primary key
                // with an encryption subkey - rather than a single key that cannot receive mail.
                "default",
                spec.expiry.ifBlank { "0" },
            ),
            timeoutMs = GpgProcess.INTERACTIVE_TIMEOUT_MS,
        )
        invalidate()
        return StatusParser.touchedFingerprints(result.stderr)
    }

    /** Adds a subkey to an existing certificate. */
    fun addSubkey(fingerprint: String, algorithm: String, usage: String, expiry: String) {
        process().require(
            listOf(
                "--no-tty",
                "--pinentry-mode", "ask",
                "--quick-add-key", fingerprint, algorithm, usage, expiry.ifBlank { "0" },
            ),
            timeoutMs = GpgProcess.INTERACTIVE_TIMEOUT_MS,
        )
        invalidate()
    }

    /**
     * Moves the expiry date of a primary key, and of [subkeys] where given.
     *
     * An empty [subkeys] list changes only the primary key: GnuPG treats "no subkey named" and
     * "this list of subkeys" as different requests, and subkeys keep their own dates.
     */
    fun setExpiry(fingerprint: String, expiry: String, subkeys: List<String> = emptyList()) {
        process().require(
            listOf("--no-tty", "--pinentry-mode", "ask", "--quick-set-expire", fingerprint, expiry) +
                subkeys,
            timeoutMs = GpgProcess.INTERACTIVE_TIMEOUT_MS,
        )
        invalidate()
    }

    /**
     * Sets owner trust by feeding `--import-ownertrust`.
     *
     * The alternative is the `trust` submenu of `--edit-key`, which only exists as an interactive
     * dialogue; a trust file is the supported way to say the same thing to a program.
     */
    fun setOwnerTrust(fingerprint: String, trust: OwnerTrust) {
        process().require(
            GpgProcess.BATCH + listOf("--import-ownertrust"),
            stdin = "$fingerprint:${trust.fileValue}:\n".toByteArray(StandardCharsets.UTF_8),
        )
        invalidate()
    }

    /** Certifies someone else's certificate with one of the user's own keys. */
    fun certify(fingerprint: String, signingKey: String?, local: Boolean, userIds: List<String>) {
        val command = if (local) "--quick-lsign-key" else "--quick-sign-key"
        val arguments = buildList {
            add("--no-tty")
            add("--pinentry-mode")
            add("ask")
            signingKey?.takeIf { it.isNotBlank() }?.let { add("--local-user"); add(it) }
            add(command)
            add(fingerprint)
            // Naming no user id certifies all of them, which is what the dialog offers by default.
            addAll(userIds)
        }
        process().require(arguments, timeoutMs = GpgProcess.INTERACTIVE_TIMEOUT_MS)
        invalidate()
    }

    fun addUserId(fingerprint: String, userId: String) {
        process().require(
            listOf("--no-tty", "--pinentry-mode", "ask", "--quick-add-uid", fingerprint, userId),
            timeoutMs = GpgProcess.INTERACTIVE_TIMEOUT_MS,
        )
        invalidate()
    }

    fun revokeUserId(fingerprint: String, userId: String) {
        process().require(
            listOf("--no-tty", "--pinentry-mode", "ask", "--quick-revoke-uid", fingerprint, userId),
            timeoutMs = GpgProcess.INTERACTIVE_TIMEOUT_MS,
        )
        invalidate()
    }

    fun setPrimaryUserId(fingerprint: String, userId: String) {
        process().require(
            listOf(
                "--no-tty", "--pinentry-mode", "ask",
                "--quick-set-primary-uid", fingerprint, userId,
            ),
            timeoutMs = GpgProcess.INTERACTIVE_TIMEOUT_MS,
        )
        invalidate()
    }

    /** Changes the passphrase of a secret key; both prompts are pinentry's, not the plugin's. */
    fun changePassphrase(fingerprint: String) {
        process().require(
            listOf("--no-tty", "--pinentry-mode", "ask", "--passwd", fingerprint),
            timeoutMs = GpgProcess.INTERACTIVE_TIMEOUT_MS,
        )
    }

    fun deleteKey(fingerprint: String, includeSecret: Boolean) {
        val gpg = process()
        if (includeSecret) {
            gpg.require(
                GpgProcess.BATCH + listOf("--delete-secret-keys", fingerprint),
                timeoutMs = GpgProcess.INTERACTIVE_TIMEOUT_MS,
            )
        }
        gpg.require(GpgProcess.BATCH + listOf("--delete-keys", fingerprint))
        invalidate()
    }

    /**
     * The revocation certificate GnuPG wrote when the key was created.
     *
     * gpg comments out the armor in that file so it cannot be imported by accident; publishing a
     * revocation is irreversible, so the comment markers are only stripped when the user asks for
     * exactly this.
     */
    fun revocationCertificate(fingerprint: String): File? {
        val home = process().homeDirectory()?.takeIf { it.isNotBlank() } ?: return null
        return File(File(home, "openpgp-revocs.d"), "$fingerprint.rev").takeIf { it.isFile }
    }

    /** Applies the stored revocation certificate, which is what "revoke this certificate" means. */
    fun revokeCertificate(fingerprint: String): ImportResult {
        val file = revocationCertificate(fingerprint)
            ?: throw GpgException(
                "No revocation certificate for this key in openpgp-revocs.d; if you kept a copy " +
                    "elsewhere, import that file instead.",
            )
        val armored = file.readLines()
            .joinToString("\n") { it.removePrefix(":") }
        return importText(armored)
    }

    // --- Keyservers ------------------------------------------------------------------------------

    private fun keyserverArguments(): List<String> =
        GpgSettings.getInstance().keyserver.trim().takeIf { it.isNotEmpty() }
            ?.let { listOf("--keyserver", it) }
            .orEmpty()

    fun searchKeyserver(query: String): List<KeyserverHit> {
        val result = process().require(
            GpgProcess.BATCH + keyserverArguments() + LIST_ARGUMENTS + listOf("--search-keys", query),
            timeoutMs = NETWORK_TIMEOUT_MS,
        )
        return ColonParser.parseSearchResults(result.stdout)
    }

    fun receiveKeys(keyIds: List<String>): ImportResult {
        val result = process().run(
            GpgProcess.BATCH + keyserverArguments() + listOf("--recv-keys") + keyIds,
            timeoutMs = NETWORK_TIMEOUT_MS,
        )
        val counts = StatusParser.import(result.stderr)
        if (counts.fingerprints.isEmpty() && !result.isSuccess) {
            throw GpgException(result.errorMessage(), result.exitCode)
        }
        invalidate()
        return counts
    }

    fun sendKeys(fingerprints: List<String>) {
        process().require(
            GpgProcess.BATCH + keyserverArguments() + listOf("--send-keys") + fingerprints,
            timeoutMs = NETWORK_TIMEOUT_MS,
        )
    }

    fun refreshFromKeyserver(fingerprints: List<String>): ImportResult {
        val result = process().run(
            GpgProcess.BATCH + keyserverArguments() + listOf("--refresh-keys") + fingerprints,
            timeoutMs = NETWORK_TIMEOUT_MS,
        )
        invalidate()
        return StatusParser.import(result.stderr)
    }

    /** Finds a key by mail address, using whatever route gpg is configured for (WKD, DANE, LDAP). */
    fun locateKey(address: String): ImportResult {
        val result = process().run(
            GpgProcess.BATCH + LIST_ARGUMENTS + listOf("--locate-external-keys", address),
            timeoutMs = NETWORK_TIMEOUT_MS,
        )
        invalidate()
        val counts = StatusParser.import(result.stderr)
        if (counts.fingerprints.isEmpty() && !result.isSuccess) {
            throw GpgException(result.errorMessage(), result.exitCode)
        }
        return counts
    }

    // --- Files -----------------------------------------------------------------------------------

    /** Encrypts a file, optionally signing it in the same pass, as Kleopatra's dialog does. */
    fun encryptFile(
        input: File,
        output: File,
        recipients: List<String>,
        armor: Boolean,
        signWith: String?,
        allowUntrusted: Boolean,
    ) {
        val arguments = buildList {
            addAll(GpgProcess.BATCH)
            if (armor) add("--armor")
            // Without this gpg refuses in batch mode as soon as one recipient is not trusted,
            // which is most of them; the dialog makes the choice explicit rather than assuming it.
            if (allowUntrusted) { add("--trust-model"); add("always") }
            recipients.forEach { add("--recipient"); add(it) }
            signWith?.takeIf { it.isNotBlank() }?.let { add("--local-user"); add(it); add("--sign") }
            add("--output")
            add(output.absolutePath)
            add("--encrypt")
            add(input.absolutePath)
        }
        process().require(arguments, timeoutMs = GpgProcess.INTERACTIVE_TIMEOUT_MS)
    }

    /** Encrypts to a passphrase rather than to a key; gpg-agent asks for the passphrase. */
    fun encryptSymmetric(input: File, output: File, armor: Boolean) {
        val arguments = buildList {
            add("--no-tty")
            add("--yes")
            add("--pinentry-mode")
            add("ask")
            if (armor) add("--armor")
            add("--output")
            add(output.absolutePath)
            add("--symmetric")
            add(input.absolutePath)
        }
        process().require(arguments, timeoutMs = GpgProcess.INTERACTIVE_TIMEOUT_MS)
    }

    fun decryptFile(input: File, output: File): DecryptionResult {
        val result = process().run(
            listOf(
                "--no-tty", "--yes", "--pinentry-mode", "ask",
                "--output", output.absolutePath,
                "--decrypt", input.absolutePath,
            ),
            timeoutMs = GpgProcess.INTERACTIVE_TIMEOUT_MS,
        )
        return StatusParser.decryption(result.stderr, output.absolutePath).also {
            if (!it.success) throw GpgException(it.failureReason.ifBlank { result.errorMessage() })
        }
    }

    fun signFile(input: File, output: File, detached: Boolean, armor: Boolean, signWith: String?) {
        val arguments = buildList {
            add("--no-tty")
            add("--yes")
            add("--pinentry-mode")
            add("ask")
            if (armor) add("--armor")
            signWith?.takeIf { it.isNotBlank() }?.let { add("--local-user"); add(it) }
            add("--output")
            add(output.absolutePath)
            add(if (detached) "--detach-sign" else "--sign")
            add(input.absolutePath)
        }
        process().require(arguments, timeoutMs = GpgProcess.INTERACTIVE_TIMEOUT_MS)
    }

    /**
     * Verifies a signature. [signedFile] is given for a detached signature and omitted for one
     * that carries its own content.
     */
    fun verifyFile(signature: File, signedFile: File?): VerificationResult {
        val arguments = GpgProcess.BATCH + listOfNotNull(
            "--verify",
            signature.absolutePath,
            signedFile?.absolutePath,
        )
        // A bad signature is a result to show, not an exception to raise, so the exit code is
        // read out of the status stream rather than thrown on.
        val result = process().run(arguments)
        return StatusParser.verification(result.stderr).let {
            if (it.signatures.isEmpty() && !result.isSuccess) {
                throw GpgException(result.errorMessage(), result.exitCode)
            }
            it
        }
    }

    // --- Text ------------------------------------------------------------------------------------

    /**
     * The editor-selection variants.
     *
     * These pipe through stdin and stdout and always use ASCII armor, so nothing in an editor
     * operation ever reaches the disk - which is the point of having them separate from the file
     * operations rather than writing a temporary file and reusing those.
     */
    fun encryptText(
        text: String,
        recipients: List<String>,
        signWith: String?,
        allowUntrusted: Boolean,
    ): String {
        val arguments = buildList {
            addAll(GpgProcess.BATCH)
            add("--armor")
            if (allowUntrusted) { add("--trust-model"); add("always") }
            recipients.forEach { add("--recipient"); add(it) }
            signWith?.takeIf { it.isNotBlank() }?.let { add("--local-user"); add(it); add("--sign") }
            add("--encrypt")
        }
        return process().require(
            arguments,
            stdin = text.toByteArray(StandardCharsets.UTF_8),
            timeoutMs = GpgProcess.INTERACTIVE_TIMEOUT_MS,
        ).stdout
    }

    fun signText(text: String, signWith: String?, clearSign: Boolean): String {
        val arguments = buildList {
            add("--no-tty")
            add("--yes")
            add("--pinentry-mode")
            add("ask")
            add("--armor")
            signWith?.takeIf { it.isNotBlank() }?.let { add("--local-user"); add(it) }
            add(if (clearSign) "--clear-sign" else "--sign")
        }
        return process().require(
            arguments,
            stdin = text.toByteArray(StandardCharsets.UTF_8),
            timeoutMs = GpgProcess.INTERACTIVE_TIMEOUT_MS,
        ).stdout
    }

    /** Returns the plaintext together with what gpg said about it. */
    fun decryptText(text: String): Pair<String, DecryptionResult> {
        val result = process().run(
            listOf("--no-tty", "--yes", "--pinentry-mode", "ask", "--decrypt"),
            stdin = text.toByteArray(StandardCharsets.UTF_8),
            timeoutMs = GpgProcess.INTERACTIVE_TIMEOUT_MS,
        )
        val outcome = StatusParser.decryption(result.stderr)
        // A clear-signed message decrypts to itself: there is nothing to decrypt, but the
        // signature is still worth reporting, so an empty "failure" with signatures is a success.
        if (!outcome.success && outcome.signatures.isEmpty()) {
            throw GpgException(outcome.failureReason.ifBlank { result.errorMessage() })
        }
        return result.stdout to outcome
    }

    fun verifyText(text: String): VerificationResult {
        val result = process().run(
            GpgProcess.BATCH + listOf("--verify"),
            stdin = text.toByteArray(StandardCharsets.UTF_8),
        )
        val verification = StatusParser.verification(result.stderr)
        if (verification.signatures.isEmpty() && !result.isSuccess) {
            throw GpgException(result.errorMessage(), result.exitCode)
        }
        return verification
    }

    companion object {
        /**
         * `--fixed-list-mode` keeps the record layout stable across versions, and the trust check
         * is suppressed because it prints progress that has nothing to do with the listing.
         */
        private val LIST_ARGUMENTS = listOf(
            "--with-colons",
            "--fixed-list-mode",
            "--with-fingerprint",
            "--with-subkey-fingerprint",
        )

        private const val NETWORK_TIMEOUT_MS = 120_000

        fun getInstance(): GpgService =
            ApplicationManager.getApplication().getService(GpgService::class.java)
    }
}
