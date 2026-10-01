package com.opendevtools.vaultwarden.service

import com.opendevtools.vaultwarden.crypto.PasswordGenerator
import com.opendevtools.vaultwarden.crypto.PasswordStrength
import com.opendevtools.vaultwarden.model.VaultItem
import com.opendevtools.vaultwarden.model.VaultReport
import com.opendevtools.vaultwarden.model.VaultReportFinding
import com.opendevtools.vaultwarden.model.VaultSnapshot

/**
 * The vault health checks, computed entirely from already-decrypted items.
 *
 * The web vault's equivalent reports include a breach check against Have I Been Pwned. That one
 * is deliberately missing here: it would send a hash prefix of every password to a third party,
 * and a plugin that promises secrets never leave the machine should not quietly do that.
 */
object VaultReports {

    fun analyse(snapshot: VaultSnapshot): VaultReport {
        val logins = snapshot.items.filter { !it.password.isNullOrBlank() }

        val weak = logins.mapNotNull { item ->
            val strength = PasswordGenerator.strength(item.password.orEmpty())
            if (strength <= PasswordStrength.WEAK) {
                VaultReportFinding(item, strength.title)
            } else {
                null
            }
        }

        // Group by the password itself; anything used more than once is a reuse finding.
        val byPassword = logins.groupBy { it.password }
        val reused = byPassword
            .filterValues { it.size > 1 }
            .flatMap { (_, items) ->
                items.map { VaultReportFinding(it, "Used by ${items.size} items") }
            }
            .sortedBy { it.item.name.lowercase() }

        val unsecured = snapshot.items.mapNotNull { item ->
            val http = item.uris.filter { it.startsWith("http://", ignoreCase = true) }
            if (http.isEmpty()) null else VaultReportFinding(item, http.joinToString(", "))
        }

        val missingTotp = snapshot.items
            .filter { it.type.code == 1 && !it.password.isNullOrBlank() && !it.hasTotp }
            .map { VaultReportFinding(it, "No one-time password configured") }

        return VaultReport(
            weakPasswords = weak.sortedBy { it.item.name.lowercase() },
            reusedPasswords = reused,
            unsecuredUris = unsecured.sortedBy { it.item.name.lowercase() },
            missingTotp = missingTotp.sortedBy { it.item.name.lowercase() },
        )
    }

    /** How many other items share this item's password; 0 when it is unique. */
    fun reuseCount(snapshot: VaultSnapshot, item: VaultItem): Int {
        val password = item.password?.takeIf { it.isNotBlank() } ?: return 0
        return snapshot.items.count { it.password == password && it.id != item.id }
    }
}
