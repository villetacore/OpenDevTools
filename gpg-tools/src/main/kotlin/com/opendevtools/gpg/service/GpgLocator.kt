package com.opendevtools.gpg.service

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.util.SystemInfo
import com.opendevtools.gpg.cli.GpgProcess
import java.io.File

/**
 * Finds the gpg executable.
 *
 * The plugin drives the user's own GnuPG rather than shipping one, so the first job on every fresh
 * install is to work out where that is. `PATH` answers it on a well-set-up machine; the fallback
 * list covers the installers that do not put gpg on `PATH`, which is the common case on Windows
 * and the reason a "gpg not found" message alone would be a poor experience.
 */
object GpgLocator {

    private const val EXECUTABLE = "gpg"

    private val WINDOWS_CANDIDATES = listOf(
        "C:\\Program Files\\GnuPG\\bin\\gpg.exe",
        "C:\\Program Files (x86)\\GnuPG\\bin\\gpg.exe",
        "C:\\Program Files\\Gpg4win\\bin\\gpg.exe",
        "C:\\Program Files (x86)\\GnuPG\\pub\\gpg.exe",
        "C:\\Program Files\\Git\\usr\\bin\\gpg.exe",
    )

    private val UNIX_CANDIDATES = listOf(
        "/usr/bin/gpg",
        "/usr/local/bin/gpg",
        "/opt/homebrew/bin/gpg",
        "/usr/local/MacGPG2/bin/gpg",
        "/run/current-system/sw/bin/gpg",
    )

    /** Every path worth offering, most likely first, with duplicates and misses removed. */
    fun candidates(): List<String> {
        val found = LinkedHashSet<String>()
        PathEnvironmentVariableUtil.findInPath(EXECUTABLE)?.let { found += it.absolutePath }
        // Some distributions still ship 2.x as `gpg2` with `gpg` left pointing at the 1.4 line.
        PathEnvironmentVariableUtil.findInPath("gpg2")?.let { found += it.absolutePath }
        found += if (SystemInfo.isWindows) WINDOWS_CANDIDATES else UNIX_CANDIDATES
        return found.filter { GpgProcess.isExecutable(it) }
    }

    fun detect(): String? = candidates().firstOrNull()

    /**
     * Checks one path by running it, which is the only way to tell a working gpg from a file that
     * merely has the right name.
     */
    fun describe(executable: String, home: String?): String {
        if (!GpgProcess.isExecutable(executable)) {
            return if (File(executable).exists()) {
                "$executable is not executable"
            } else {
                "$executable does not exist"
            }
        }
        val process = GpgProcess(executable, home)
        val version = process.version() ?: return "$executable did not answer --version"
        val keyringHome = process.homeDirectory() ?: "unknown"
        return "$version\nKeyring: $keyringHome"
    }
}
