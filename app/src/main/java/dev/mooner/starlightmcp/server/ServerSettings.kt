package dev.mooner.starlightmcp.server

/** Settings read for each request; changing them needs no restart. */
data class ServerSettings(
    /** SHA-256 hex of the access token, or null if none is set. */
    val tokenHash: String? = null,
    /** Without a token, every request is rejected while this is on. */
    val requireToken: Boolean = true,
    val maxBodyBytes: Long = 4L * 1024 * 1024,
    val logCalls: Boolean = false,
)
