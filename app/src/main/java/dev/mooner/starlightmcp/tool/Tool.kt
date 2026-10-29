package dev.mooner.starlightmcp.tool

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What `tools/list` tells clients about a tool. */
class ToolSpec(
    val name: String,
    val description: String,
    /** A JSON Schema of type object. */
    val inputSchema: JsonObject,
    val title: String? = null,
    /** Hints such as `readOnlyHint`, passed through as is. */
    val annotations: JsonObject? = null,
) {

    init {
        require(NAME_REGEX.matches(name)) { "Invalid tool name '$name': use 1-64 letters, digits, '_' or '-'" }
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        title?.let { put("title", it) }
        put("description", description)
        put("inputSchema", inputSchema)
        annotations?.let { put("annotations", it) }
    }

    companion object {
        // What the Claude API accepts; MCP also allows '.', which some clients reject.
        val NAME_REGEX = Regex("[A-Za-z0-9_-]{1,64}")
    }
}

/** The result of `tools/call`. Failures of the tool itself are results with [isError], not protocol errors. */
class ToolResult(
    val content: JsonArray,
    val isError: Boolean = false,
    val structuredContent: JsonObject? = null,
) {

    fun toJson(): JsonObject = buildJsonObject {
        put("content", content)
        structuredContent?.let { put("structuredContent", it) }
        if (isError)
            put("isError", true)
    }

    companion object {

        fun text(text: String, isError: Boolean = false): ToolResult =
            ToolResult(buildJsonArray { add(textContent(text)) }, isError)

        fun error(message: String): ToolResult =
            text(message, isError = true)

        fun textContent(text: String): JsonObject = buildJsonObject {
            put("type", "text")
            put("text", text)
        }

        /** A result written by a script as `{ content: [...], isError?, structuredContent? }`. */
        fun fromRaw(raw: JsonObject): ToolResult? {
            val content = raw["content"] as? JsonArray ?: return null
            return ToolResult(
                content = content,
                isError = (raw["isError"] as? JsonPrimitive)?.content == "true",
                structuredContent = raw["structuredContent"] as? JsonObject,
            )
        }
    }
}

/** Runs a tool with the arguments sent by the client. */
fun interface ToolInvoker {
    suspend fun invoke(arguments: JsonObject): ToolResult
}
