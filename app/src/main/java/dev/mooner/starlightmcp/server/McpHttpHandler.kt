package dev.mooner.starlightmcp.server

import dev.mooner.starlightmcp.protocol.JsonRpc
import dev.mooner.starlightmcp.protocol.McpProtocol
import dev.mooner.starlightmcp.utils.sha256Hex
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

/**
 * The Streamable HTTP transport of MCP, without sessions or server-sent events: each POST to [ENDPOINT] carries
 * messages and gets their responses as JSON.
 */
class McpHttpHandler(
    private val protocol: McpProtocol,
    private val settings: () -> ServerSettings,
    private val log: (message: String) -> Unit,
) {

    suspend fun handle(call: ApplicationCall) {
        val method = call.request.httpMethod
        val path = call.request.path()
        try {
            route(call, method, path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log("Failed to handle ${method.value} $path: $e")
            if (!call.response.isCommitted)
                respondError(call, HttpStatusCode.InternalServerError)
        }
    }

    private suspend fun route(call: ApplicationCall, method: HttpMethod, path: String) {
        if (path.trimEnd('/') != ENDPOINT)
            return respondError(call, HttpStatusCode.NotFound)
        // Keeps web pages from reaching the server through DNS rebinding.
        if (!isAllowedOrigin(call))
            return respondError(call, HttpStatusCode.Forbidden, "Origin not allowed")
        val settings = settings()
        if (settings.requireToken && !isAuthorized(call, settings.tokenHash)) {
            call.response.header(HttpHeaders.WWWAuthenticate, "Bearer")
            return respondError(call, HttpStatusCode.Unauthorized)
        }
        // Without sessions there is no stream to open (GET) or session to end (DELETE).
        if (method != HttpMethod.Post) {
            call.response.header(HttpHeaders.Allow, HttpMethod.Post.value)
            return respondError(call, HttpStatusCode.MethodNotAllowed)
        }

        val body = receiveBody(call, settings.maxBodyBytes)
            ?: return respondError(call, HttpStatusCode.PayloadTooLarge)
        val message = try {
            Json.parseToJsonElement(body.decodeToString())
        } catch (e: Exception) {
            return respondJson(call, JsonRpc.error(null, JsonRpc.PARSE_ERROR, "Parse error: ${e.message}"), HttpStatusCode.BadRequest)
        }
        val response: JsonElement? = if (message is JsonArray) {
            // Batches were dropped in 2025-06-18, but older clients may send them.
            if (message.isEmpty())
                JsonRpc.error(null, JsonRpc.INVALID_REQUEST, "Empty batch")
            else
                message.mapNotNull { protocol.handle(it) }.takeIf { it.isNotEmpty() }?.let(::JsonArray)
        } else {
            protocol.handle(message)
        }
        if (response == null)
            call.respond(HttpStatusCode.Accepted)
        else
            respondJson(call, response, HttpStatusCode.OK)
    }

    /** No Origin (not a browser), or a page served from this device or this server. */
    private fun isAllowedOrigin(call: ApplicationCall): Boolean {
        val origin = call.request.headers[HttpHeaders.Origin] ?: return true
        val originHost = runCatching { Url(origin).host }.getOrNull() ?: return false
        if (originHost in LOCAL_HOSTS)
            return true
        val host = call.request.headers[HttpHeaders.Host]?.substringBeforeLast(':')?.removeSurrounding("[", "]")
        return originHost.removeSurrounding("[", "]") == host
    }

    /** The body, or null if it is larger than [limit]. */
    private suspend fun receiveBody(call: ApplicationCall, limit: Long): ByteArray? {
        val length = call.request.contentLength()
        if (length != null && length > limit)
            return null
        val bytes = call.receiveChannel().readBuffer(limit + 1).readByteArray()
        return bytes.takeIf { it.size <= limit }
    }

    private fun isAuthorized(call: ApplicationCall, tokenHash: String?): Boolean {
        if (tokenHash == null)
            return false
        val token = call.request.headers[HttpHeaders.Authorization]
            ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
            ?.substring(BEARER_PREFIX.length)
            ?.trim()
            ?: call.request.queryParameters[TOKEN_QUERY]
            ?: return false
        return MessageDigest.isEqual(sha256Hex(token).toByteArray(), tokenHash.toByteArray())
    }

    private suspend fun respondJson(call: ApplicationCall, json: JsonElement, status: HttpStatusCode) {
        call.respondText(json.toString(), ContentType.Application.Json, status)
    }

    private suspend fun respondError(call: ApplicationCall, status: HttpStatusCode, message: String = status.description) {
        val json = buildJsonObject {
            put("status", status.value)
            put("error", message)
        }
        respondJson(call, json, status)
    }

    companion object {
        const val ENDPOINT = "/mcp"

        private const val BEARER_PREFIX = "Bearer "
        private const val TOKEN_QUERY = "token"
        private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "[::1]", "::1")
    }
}
