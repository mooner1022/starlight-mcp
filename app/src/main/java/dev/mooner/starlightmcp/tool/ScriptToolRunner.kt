package dev.mooner.starlightmcp.tool

import dev.mooner.starlightmcp.script.ScriptValues
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Calls tools written in scripts on their project's threads, within the time limit. */
class ScriptToolRunner(
    /** Runs handlers, so a handler that outlives its timeout doesn't hold the request. */
    private val handlerScope: CoroutineScope,
    private val timeoutMillis: () -> Long,
) {

    suspend fun run(entry: ToolRegistry.Entry, arguments: JsonObject): ToolResult {
        val owner = entry.owner
        val name = entry.spec.name
        if (!owner.isActive)
            return ToolResult.error("Project '${owner.name}' is not running")

        val timeout = timeoutMillis()
        val deadline = System.currentTimeMillis() + timeout
        // Script values are read on the project's threads too.
        val call = handlerScope.async(owner.dispatcher) {
            val value = ScriptValues.await(entry.function.call(arguments), deadline - System.currentTimeMillis())
            toResult(value)
        }
        val result = withTimeoutOrNull(timeout) {
            try {
                call.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // A promise still pending at the deadline fails the wait for it.
                if (System.currentTimeMillis() >= deadline)
                    return@withTimeoutOrNull null
                val message = e.message ?: e.toString()
                owner.logError("[MCP] Error in tool $name: $message")
                ToolResult.error(message)
            }
        }
        if (result == null) {
            call.cancel()
            owner.logError("[MCP] Tool $name returned no result in ${timeout}ms")
            return ToolResult.error("Tool $name timed out after ${timeout}ms")
        }
        return result
    }

    companion object {

        /** Strings, numbers and booleans as text, `{ content: [...] }` as is, and other values as JSON text. */
        fun toResult(value: Any?): ToolResult {
            if (ScriptValues.isAbsentResult(value))
                return ToolResult(JsonArray(emptyList()))
            ScriptValues.primitiveText(value)?.let { return ToolResult.text(it) }
            val json = ScriptValues.toJsonElement(value)
            if (json is JsonObject)
                ToolResult.fromRaw(json)?.let { return it }
            return ToolResult.text(json.toString())
        }
    }
}
