package com.opendevtools.core.secrets

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * Thin wrapper over the IDE password safe. Secrets never reach the plugin's settings XML, so a
 * synced or committed configuration directory cannot leak a token or a password.
 */
class SecretStore(private val subsystem: String) {

    private fun attributes(key: String) = CredentialAttributes(generateServiceName(subsystem, key))

    fun get(key: String): String? = PasswordSafe.instance.getPassword(attributes(key))

    fun set(key: String, secret: String?) {
        PasswordSafe.instance.setPassword(attributes(key), secret?.takeIf { it.isNotEmpty() })
    }

    fun clear(key: String) = set(key, null)
}
