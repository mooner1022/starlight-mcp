package dev.mooner.starlightmcp.server

/** What scripts and the config screen see of the server. */
data class ServerInfo(
    val isRunning: Boolean,
    val port: Int,
    /** Endpoint URLs, such as `http://192.168.0.2:8765/mcp`. */
    val urls: List<String>,
)
