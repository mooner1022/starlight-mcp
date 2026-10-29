package dev.mooner.starlightmcp.server

sealed interface ServerState {

    data object Stopped : ServerState

    data object Starting : ServerState

    data class Running(val host: String, val port: Int, val startedAt: Long) : ServerState

    data class Failed(val reason: String) : ServerState
}
