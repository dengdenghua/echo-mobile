package com.apk.claw.android.account

import java.io.IOException

/**
 * The account server answered with a non-2xx status. [detail] is FastAPI's `{"detail": "..."}`,
 * which the server already words for the user. The message keeps the old format, so callers that
 * only read `message` see no change.
 */
class AccountHttpException(val code: Int, val detail: String?, body: String) :
    RuntimeException(detail ?: "HTTP $code: ${body.take(BODY_PREVIEW_CHARS)}") {
    private companion object {
        const val BODY_PREVIEW_CHARS = 200
    }
}

/**
 * What a failed account call should tell the user.
 *
 * - No connection, DNS, timeout or TLS failure (any [IOException]): [network]. The exception's own
 *   message is a raw English platform string such as `Unable to resolve host "api.octoapk.com"`,
 *   which the login screen used to show as is.
 * - A server answer with a detail: that detail.
 * - A server error (5xx) without one: [server] for that status code.
 * - Anything else: its message, or [fallback] when it has none.
 */
object AccountErrorText {
    private const val FIRST_SERVER_ERROR = 500

    fun of(
        error: Throwable,
        fallback: String,
        network: String,
        server: (code: Int) -> String,
    ): String = when {
        error is IOException -> network
        error is AccountHttpException && !error.detail.isNullOrBlank() -> error.detail
        error is AccountHttpException && error.code >= FIRST_SERVER_ERROR -> server(error.code)
        error is AccountHttpException -> fallback
        else -> error.message?.takeIf { it.isNotBlank() } ?: fallback
    }
}
