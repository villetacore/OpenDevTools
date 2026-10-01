package com.opendevtools.vaultwarden.api

/**
 * Anything the API layer refuses to do or the server refused to do. [statusCode] is null for
 * failures that never reached the server, such as an incomplete configuration.
 */
class VaultwardenException(
    message: String,
    cause: Throwable? = null,
    val statusCode: Int? = null,
) : RuntimeException(message, cause) {

    val isAuthFailure: Boolean get() = statusCode == 401
    val isConflict: Boolean get() = statusCode == 409
}
