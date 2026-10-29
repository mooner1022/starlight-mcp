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

    internal fun setState(state: ServerState) {
        mState.value = state
    }

    fun serverInfo(): ServerInfo =
        when (val state = state.value) {
            is ServerState.Running -> ServerInfo(true, state.port, serverUrls(state.host, state.port).map { it + McpHttpHandler.ENDPOINT })
            else                   -> ServerInfo(false, configuredPort, emptyList())
        }

    const val DEFAULT_PORT = 8765
}
