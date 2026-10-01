package com.opendevtools.proxmox.model

/**
 * A guest snapshot. Proxmox always reports a synthetic `current` entry that stands for the live
 * state; it is kept here so the tree can show the same shape as the web UI.
 */
data class GuestSnapshot(
    val name: String,
    val description: String?,
    val parent: String?,
    val createdAt: Long,
    val withRam: Boolean,
    val running: Boolean = false,
) {
    val isCurrentState: Boolean get() = name == CURRENT

    val key: String get() = "snapshot:$name"

    companion object {
        const val CURRENT = "current"

        /** Snapshot names Proxmox accepts: letters, digits, underscore and hyphen. */
        private val VALID_NAME = Regex("^[A-Za-z][A-Za-z0-9_\\-]{1,39}$")

        fun validateName(name: String): String? = when {
            name.isBlank() -> "Enter a snapshot name"
            name == CURRENT -> "'current' is reserved for the live state"
            !VALID_NAME.matches(name) ->
                "Start with a letter and use only letters, digits, '_' and '-' (2-40 characters)"

            else -> null
        }
    }
}
