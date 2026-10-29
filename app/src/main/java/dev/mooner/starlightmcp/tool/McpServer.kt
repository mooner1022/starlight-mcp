package dev.mooner.starlightmcp.tool

import dev.mooner.starlightmcp.script.ScriptValues
import dev.mooner.starlightmcp.server.ServerInfo
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The MCP tools of one project, given to scripts by the `onMcpSetup(server)` event and as the `Mcp` API.
 *
 * ```js
 * export function onMcpSetup(server) {
 *     server.tool("greet", { description: "Greets someone", inputSchema: { name: "string" } },
 *         (args) => `Hello, ${args.name}!`);
 * }
 * ```
 */
class McpServer(
    private val owner: ToolOwner,
    private val gen: Long,
    private val registry: ToolRegistry,
    private val toolsets: ProjectToolsets,
    private val serverInfo: () -> ServerInfo,
) {

    /** Identifies the compilation this server belongs to. */
    val generation: Long
        get() = gen

    /**
     * Registers a tool.
     *
     * [options] is the description, or an object with `description`, `title`, `inputSchema` and `annotations`.
     * `inputSchema` is a JSON Schema of an object, or in short a map of argument names to types:
     * `{ city: "string", days: "integer?" }` (a trailing `?` makes the argument optional; values may also be schemas).
     *
     * [handler] gets the arguments as an object and returns the result; see [ScriptFunction].
     */
    fun tool(name: String, options: Any?, handler: Any?): McpServer {
        if (!isCurrent()) {
            owner.logError("[MCP] Ignored tool $name: the script was recompiled since this server was given")
            return this
        }
        require(!name.startsWith(RESERVED_PREFIX)) { "Tool names starting with '$RESERVED_PREFIX' are reserved: $name" }
        val spec = parseSpec(name, options)
        val shadowedBy = registry.add(owner, gen, spec, ScriptFunction.of(handler))
        if (shadowedBy != null)
            owner.logError("[MCP] Tool $name is already taken by project '${shadowedBy.owner.name}'")
        return this
    }

    fun remove(name: String): Boolean =
        isCurrent() && registry.remove(owner.id, name)

    /** Removes every tool of this project. */
    fun clear() {
        if (isCurrent())
            registry.removeOwner(owner.id)
    }

    /** Names of this project's tools. */
    fun getTools(): List<String> =
        registry.toolsOf(owner.id).map { it.spec.name }

    fun isRunning(): Boolean =
        serverInfo().isRunning

    fun getPort(): Int =
        serverInfo().port

    /** Addresses clients connect to, such as `http://192.168.0.2:8765/mcp`. */
    fun getUrls(): List<String> =
        serverInfo().urls

    private fun isCurrent(): Boolean =
        toolsets.isCurrent(owner.id, gen)

    override fun toString(): String =
        "McpServer[${owner.name}]"

    companion object {

        /** Prefix of the built-in tools. */
        const val RESERVED_PREFIX = "starlight_"

        private val TYPES = setOf("string", "number", "integer", "boolean", "object", "array", "null")

        internal fun parseSpec(name: String, options: Any?): ToolSpec {
            val text = ScriptValues.primitiveText(options)
            val json = if (text != null || ScriptValues.isAbsent(options)) null else ScriptValues.toJsonElement(options)
            require(text != null || json == null || json is JsonObject) { "Options of tool $name must be a string or an object" }
            val obj = json as? JsonObject
            return ToolSpec(
                name = name,
                description = text ?: obj?.string("description").orEmpty(),
                inputSchema = inputSchemaOf(name, obj?.get("inputSchema")),
                title = obj?.string("title"),
                annotations = obj?.get("annotations") as? JsonObject,
            )
        }

        /** A full schema is kept as is; a map of names to types is expanded. */
        internal fun inputSchemaOf(name: String, value: JsonElement?): JsonObject {
            if (value == null || value is JsonNull)
                return buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {}
                }
            require(value is JsonObject) { "inputSchema of tool $name must be an object" }
            if (value["type"] is JsonPrimitive)
                return value
            val required = arrayListOf<String>()
            val properties = buildJsonObject {
                for ((arg, type) in value) {
                    val typeName = (type as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (typeName == null) {
                        require(type is JsonObject) { "Type of argument $arg of tool $name must be a type name or a schema" }
                        put(arg, type)
                        required += arg
                        continue
                    }
                    val isOptional = typeName.endsWith("?")
                    val baseType = typeName.removeSuffix("?")
                    require(baseType in TYPES) { "Unknown type '$typeName' of argument $arg of tool $name" }
                    put(arg, buildJsonObject { put("type", baseType) })
                    if (!isOptional)
                        required += arg
                }
            }
            return buildJsonObject {
                put("type", "object")
                put("properties", properties)
                if (required.isNotEmpty())
                    put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
            }
        }

        private fun JsonObject.string(key: String): String? =
            (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
