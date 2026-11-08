package dev.mooner.starlightmcp.server

import dev.mooner.starlightktor.server.KtorServer
import dev.mooner.starlightktor.server.KtorServers
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call

/** The name other plugins see in [dev.mooner.starlightktor.server.PortInUseException]. */
private const val OWNER = "MCP Server"

/**
 * Serves this handler on [host]:[port] through the Ktor plugin, which also guards against crashes and port clashes.
 * Blocks while binding; see [KtorServers.start] for what it throws.
 */
fun McpHttpHandler.serve(host: String, port: Int, onError: (Throwable) -> Unit = {}): KtorServer =
    KtorServers.start(OWNER, host, port, onError) {
        intercept(ApplicationCallPipeline.Call) {
            handle(call)
            finish()
        }
    }

/** The installed Ktor plugin's API level: 0 for releases before 3.6.2, which lack [KtorServers]. */
fun ktorApiLevel(): Int =
    try {
        KtorServers.apiLevel
    } catch (e: LinkageError) {
        0
    }
