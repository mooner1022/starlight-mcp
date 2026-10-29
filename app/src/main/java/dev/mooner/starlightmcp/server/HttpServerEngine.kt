package dev.mooner.starlightmcp.server

import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer

/** The Ktor CIO server. Not thread-safe: start and stop it from one thread. */
class HttpServerEngine(
    private val handler: McpHttpHandler,
) {

    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    val isRunning: Boolean
        get() = server != null

    /** Binds [host]:[port]; throws if the address can't be bound. */
    fun start(host: String, port: Int) {
        check(server == null) { "Server is already running" }
        val server = embeddedServer(CIO, port = port, host = host) {
            intercept(ApplicationCallPipeline.Call) {
                handler.handle(call)
                finish()
            }
        }
        try {
            server.start(wait = false)
        } catch (e: Throwable) {
            runCatching { server.stop(0, 0) }
            throw e
        }
        this.server = server
    }

    fun stop() {
        val server = server ?: return
        this.server = null
        server.stop(GRACE_PERIOD_MILLIS, STOP_TIMEOUT_MILLIS)
    }

    private companion object {
        const val GRACE_PERIOD_MILLIS = 500L
        const val STOP_TIMEOUT_MILLIS = 3_000L
    }
}
