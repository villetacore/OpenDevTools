package com.opendevtools.gpg.cli

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.nio.charset.StandardCharsets

/** A gpg invocation that failed, carrying enough of the output to say why. */
class GpgException(message: String, val exitCode: Int = -1, cause: Throwable? = null) :
    RuntimeException(message, cause) {

    /** The user dismissed the pinentry dialog; not an error worth a red balloon. */
    var cancelled: Boolean = false
        private set

    fun asCancelled(): GpgException = also { it.cancelled = true }
}

/** What one gpg run produced. */
class GpgResult(
    val exitCode: Int,
    /** The data channel: a listing, an armored key, a plaintext. Never carries status lines. */
    val stdout: String,
    /** Human messages *and* the `[GNUPG:]` status stream, which is asked for on this channel. */
    val stderr: String,
    val timedOut: Boolean,
) {
    val isSuccess: Boolean get() = exitCode == 0 && !timedOut

    val status: List<StatusLine> by lazy { StatusParser.lines(stderr) }

    val wasCancelled: Boolean get() = StatusParser.wasCancelled(status)

    /**
     * The best sentence available about a failure.
     *
     * gpg writes its human-readable errors in the user's own language, so they are shown as-is
     * rather than matched on; the status stream is what the code branches on.
     */
    fun errorMessage(): String {
        if (timedOut) return "gpg did not finish in time and was stopped"
        val failure = status.firstOrNull { it.keyword == "FAILURE" || it.keyword == "ERROR" }
        val human = stderr.lineSequence()
            .filterNot { it.startsWith("[GNUPG:]") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        return when {
            human.isNotEmpty() -> human.takeLast(MAX_ERROR_LINES).joinToString("; ")
            failure != null -> "gpg reported ${failure.keyword} ${failure.args.joinToString(" ")}"
            else -> "gpg exited with code $exitCode"
        }
    }

    private companion object {
        const val MAX_ERROR_LINES = 3
    }
}

/**
 * Runs the gpg executable.
 *
 * The plugin is a front end to the user's own GnuPG installation, the way Kleopatra is: it never
 * implements OpenPGP itself. That has one consequence worth stating plainly - passphrases are
 * handled by gpg-agent through its own pinentry dialog, so no private key and no passphrase ever
 * passes through this process.
 */
class GpgProcess(
    private val executable: String,
    /** An explicit `GNUPGHOME`, for a keyring somewhere other than the default. */
    private val home: String?,
) {

    /**
     * Runs gpg and captures both channels.
     *
     * [stdin] is written from its own thread: gpg can fill the output pipe while still reading its
     * input, and writing both from this thread would deadlock the pair.
     */
    fun run(
        arguments: List<String>,
        stdin: ByteArray? = null,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): GpgResult {
        val commandLine = GeneralCommandLine(listOf(executable) + COMMON_ARGUMENTS + arguments)
            .withCharset(StandardCharsets.UTF_8)
        home?.takeIf { it.isNotBlank() }?.let { commandLine.withEnvironment("GNUPGHOME", it) }

        LOG.debug("running gpg " + arguments.joinToString(" "))

        val handler = try {
            CapturingProcessHandler(commandLine)
        } catch (e: Exception) {
            throw GpgException("Cannot start $executable: " + (e.message ?: e.toString()), cause = e)
        }

        val writer = stdin?.let { bytes ->
            Thread({
                runCatching {
                    handler.processInput.use { stream ->
                        stream.write(bytes)
                        stream.flush()
                    }
                }
            }, "gpg-stdin").apply { isDaemon = true; start() }
        }
        if (stdin == null) {
            // gpg waits on its input for some subcommands; an immediate EOF is the answer.
            runCatching { handler.processInput.close() }
        }

        val output = handler.runProcess(timeoutMs, true)
        writer?.join(STDIN_JOIN_MS)

        val result = GpgResult(output.exitCode, output.stdout, output.stderr, output.isTimeout)
        if (!result.isSuccess) {
            LOG.debug("gpg exited " + result.exitCode + ": " + result.errorMessage())
        }
        return result
    }

    /** Runs gpg and turns a non-zero exit into an exception; used wherever failure is not a value. */
    fun require(
        arguments: List<String>,
        stdin: ByteArray? = null,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): GpgResult {
        val result = run(arguments, stdin, timeoutMs)
        if (result.isSuccess) return result
        val error = GpgException(result.errorMessage(), result.exitCode)
        throw if (result.wasCancelled) error.asCancelled() else error
    }

    fun version(): String? {
        val result = runCatching { run(listOf("--version"), timeoutMs = VERSION_TIMEOUT_MS) }.getOrNull()
        return result?.takeIf { it.isSuccess }?.stdout?.lineSequence()?.firstOrNull()?.trim()
    }

    /** Where this gpg keeps its keyring, which the settings page shows so it can be checked. */
    fun homeDirectory(): String? {
        val explicit = home?.takeIf { it.isNotBlank() }
        if (explicit != null) return explicit
        val result = runCatching { run(listOf("--version"), timeoutMs = VERSION_TIMEOUT_MS) }.getOrNull()
        return result?.stdout?.lineSequence()
            ?.firstOrNull { it.startsWith("Home: ") }
            ?.removePrefix("Home: ")
            ?.trim()
    }

    companion object {
        private val LOG = Logger.getInstance(GpgProcess::class.java)

        /**
         * Arguments every call carries.
         *
         * `--status-fd 2` puts the machine-readable stream on stderr so that stdout stays exactly
         * the data gpg was asked for - an armored key, a listing, a plaintext - with nothing mixed
         * in. `--no-greeting` and `--no-permission-warning` suppress banner lines that would
         * otherwise be mistaken for errors.
         */
        private val COMMON_ARGUMENTS = listOf(
            "--status-fd", "2",
            "--no-greeting",
            "--no-permission-warning",
            "--exit-on-status-write-error",
        )

        /**
         * Added to every call that cannot possibly need to ask the user anything.
         *
         * This is deliberately *not* part of [COMMON_ARGUMENTS]. Under `--batch` and with no
         * `--passphrase`, `--quick-generate-key` produces a key with no passphrase at all rather
         * than prompting - so key generation must run without it.
         */
        val BATCH = listOf("--batch", "--no-tty", "--yes")

        const val DEFAULT_TIMEOUT_MS = 60_000

        /** Anything that waits on a pinentry dialog is waiting on a person, not on a computer. */
        const val INTERACTIVE_TIMEOUT_MS = 300_000

        private const val VERSION_TIMEOUT_MS = 10_000
        private const val STDIN_JOIN_MS = 2_000L

        /** Whether a path is worth trying at all, before spending a process on it. */
        fun isExecutable(path: String?): Boolean {
            val file = path?.takeIf { it.isNotBlank() }?.let { File(it) } ?: return false
            return file.isFile && file.canExecute()
        }
    }
}
