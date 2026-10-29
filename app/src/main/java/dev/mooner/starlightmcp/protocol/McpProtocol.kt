package dev.mooner.starlightmcp.protocol

import dev.mooner.starlightmcp.tool.ScriptToolRunner
import dev.mooner.starlightmcp.tool.ToolInvoker
import dev.mooner.starlightmcp.tool.ToolRegistry
import dev.mooner.starlightmcp.tool.ToolResult
import dev.mooner.starlightmcp.tool.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** A tool of the plugin itself. */
class BuiltinTool(
    val spec: ToolSpec,
    val invoker: ToolInvoker,
)

/**
 * Answers MCP messages. Stateless: nothing is kept between requests, so any request may come without `initialize`.
 *
 * Built-in tools come first; a project tool of the same name is never shown.
 */
class McpProtocol(
    private val serverName: String,
    private val serverVersion: String,
    private val instructions: String?,
    private val builtins: () -> List<BuiltinTool>,
    private val registry: ToolRegistry,
    private val runner: ScriptToolRunner,
    private val onToolCall: (name: String, project: String?, result: ToolResult, durationMillis: Long) -> Unit = { _, _, _, _ -> },
) {

    /** The response to [message], or null if it is a notification or a response. */
    suspend fun handle(message: JsonElement): JsonObject? {
        if (message !is JsonObject || (message["jsonrpc"] as? JsonPrimitive)?.content != JsonRpc.VERSION)
            return JsonRpc.error(null, JsonRpc.INVALID_REQUEST, "Not a JSON-RPC 2.0 message")
        val method = (message["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val id = message["id"]
        if (method == null) {
            // A response to a request of ours, which we never send.
            return if (id != null && ("result" in message || "error" in message)) null
            else JsonRpc.error(id, JsonRpc.INVALID_REQUEST, "Missing method")
        }
        if (id == null || id is JsonNull)
            return null

        val params = when (val params = message["params"]) {
            null, JsonNull -> JsonObject(emptyMap())
            is JsonObject  -> params
            else           -> return JsonRpc.error(id, JsonRpc.INVALID_PARAMS, "params must be an object")
        }
        return try {
            JsonRpc.result(id, dispatch(method, params))
        } catch (e: CancellationException) {
            throw e
        } catch (e: JsonRpcException) {
            JsonRpc.error(id, e.code, e.message.orEmpty())
        } catch (e: Throwable) {
            JsonRpc.error(id, JsonRpc.INTERNAL_ERROR, e.message ?: e.toString())
        }
    }

    private suspend fun dispatch(method: String, params: JsonObject): JsonObject =
        when (method) {
            "initialize" -> initialize(params)
            "ping"       -> JsonObject(emptyMap())
            "tools/list" -> listTools()
            "tools/call" -> callTool(params)
            else         -> throw JsonRpcException(JsonRpc.METHOD_NOT_FOUND, "Method not found: $method")
        }

    private fun initialize(params: JsonObject): JsonObject {
        val requested = (params["protocolVersion"] as? JsonPrimitive)?.content
        return buildJsonObject {
            put("protocolVersion", requested?.takeIf { it in SUPPORTED_VERSIONS } ?: SUPPORTED_VERSIONS.first())
            putJsonObject("capabilities") {
                putJsonObject("tools") {}
            }
            putJsonObject("serverInfo") {
                put("name", serverName)
                put("version", serverVersion)
            }
            instructions?.let { put("instructions", it) }
        }
    }

    private fun listTools(): JsonObject {
        val builtins = builtins()
        val builtinNames = builtins.mapTo(hashSetOf()) { it.spec.name }
        val tools = buildJsonArray {
            builtins.forEach { add(it.spec.toJson()) }
            registry.visible()
                .filter { it.spec.name !in builtinNames }
                .forEach { add(it.spec.toJson()) }
        }
        return buildJsonObject { put("tools", tools) }
    }

    private suspend fun callTool(params: JsonObject): JsonObject {
        val name = (params["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw JsonRpcException(JsonRpc.INVALID_PARAMS, "Missing tool name")
        val arguments = when (val arguments = params["arguments"]) {
            null, JsonNull -> JsonObject(emptyMap())
            is JsonObject  -> arguments
            else           -> throw JsonRpcException(JsonRpc.INVALID_PARAMS, "arguments must be an object")
        }
        val startNanos = System.nanoTime()
        val builtin = builtins().firstOrNull { it.spec.name == name }
        val project: String?
        val result = if (builtin != null) {
            project = null
            try {
                builtin.invoker.invoke(arguments)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ToolResult.error(e.message ?: e.toString())
            }
        } else {
            val entry = registry.find(name)
                ?: throw JsonRpcException(JsonRpc.INVALID_PARAMS, "Unknown tool: $name")
            project = entry.owner.name
            runner.run(entry, arguments)
        }
        onToolCall(name, project, result, (System.nanoTime() - startNanos) / 1_000_000)
        return result.toJson()
    }

    companion object {
        /** Newest first; a client asking for another version gets the newest. */
        val SUPPORTED_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")
    }
}

/** Arguments of a tool, read by built-in tools. */
internal fun JsonObject.stringArg(name: String): String? =
    (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.requireString(name: String): String =
    stringArg(name) ?: throw IllegalArgumentException("Missing argument: $name")

internal fun JsonObject.intArg(name: String): Int? =
    (get(name) as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()

internal fun JsonObject.booleanArg(name: String): Boolean? =
    (get(name) as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

