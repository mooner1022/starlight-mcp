package dev.mooner.starlightmcp

import dev.mooner.starlightmcp.server.McpHttpHandler
import dev.mooner.starlightmcp.server.ServerInfo
import dev.mooner.starlightmcp.server.ServerState
import dev.mooner.starlightmcp.tool.ProjectToolsets
import dev.mooner.starlightmcp.tool.ToolRegistry
import dev.mooner.starlightmcp.utils.serverUrls
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** State shared by the plugin, the scripts' servers and the API. */
object McpRuntime {

    val registry = ToolRegistry()

    val toolsets = ProjectToolsets(registry, ::serverInfo)

    private val mState = MutableStateFlow<ServerState>(ServerState.Stopped)
    val state: StateFlow<ServerState> = mState.asStateFlow()

    /** The port in the config, reported while the server is not running. */
    @Volatile
    var configuredPort: Int = DEFAULT_PORT
        internal set

    private val mRecentCalls = MutableStateFlow<List<String>>(emptyList())
    /** The last tool calls, newest first, as shown in the settings. */
    val recentCalls: StateFlow<List<String>> = mRecentCalls.asStateFlow()

    internal fun setState(state: ServerState) {
        mState.value = state
    }

    internal fun recordCall(line: String) {
        mRecentCalls.update { calls -> (listOf(line) + calls).take(RECENT_CALLS) }
    }

    fun serverInfo(): ServerInfo =
        when (val state = state.value) {
            is ServerState.Running -> ServerInfo(true, state.port, serverUrls(state.host, state.port).map { it + McpHttpHandler.ENDPOINT })
            else                   -> ServerInfo(false, configuredPort, emptyList())
        }

    const val DEFAULT_PORT = 8765
    private const val RECENT_CALLS = 20
}
