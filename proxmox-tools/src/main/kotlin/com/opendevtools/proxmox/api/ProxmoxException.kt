package com.opendevtools.proxmox.api

/**
 * Anything the Proxmox layer refuses to do or the server refused to do. [statusCode] is null for
 * failures that never reached the server, such as an incomplete configuration.
 */
class ProxmoxException(
    message: String,
    cause: Throwable? = null,
    val statusCode: Int? = null,
) : RuntimeException(message, cause) {

    val isAuthFailure: Boolean get() = statusCode == 401
    val isPermissionFailure: Boolean get() = statusCode == 403
}
