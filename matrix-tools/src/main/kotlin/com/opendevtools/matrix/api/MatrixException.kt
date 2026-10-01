package com.opendevtools.matrix.api

/**
 * A failure from the homeserver or from the plugin's own checks. [errorCode] is the Matrix
 * `errcode` such as `M_FORBIDDEN` or `M_LIMIT_EXCEEDED`, which is far more actionable than the
 * status code alone.
 */
class MatrixException(
    message: String,
    cause: Throwable? = null,
    val statusCode: Int? = null,
    val errorCode: String? = null,
    /** Milliseconds the server asked us to wait, for `M_LIMIT_EXCEEDED`. */
    val retryAfterMs: Long? = null,
) : RuntimeException(message, cause) {

    val isUnauthorized: Boolean get() = errorCode == "M_UNKNOWN_TOKEN" || statusCode == 401

    val isRateLimited: Boolean get() = errorCode == "M_LIMIT_EXCEEDED" || statusCode == 429

    val isForbidden: Boolean get() = errorCode == "M_FORBIDDEN" || statusCode == 403
}
