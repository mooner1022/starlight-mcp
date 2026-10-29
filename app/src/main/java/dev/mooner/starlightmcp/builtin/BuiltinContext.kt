package dev.mooner.starlightmcp.builtin

import dev.mooner.starlightmcp.protocol.JsonRpc
import dev.mooner.starlightmcp.protocol.JsonRpcException
import dev.mooner.starlightmcp.tool.McpServer
import dev.mooner.starlightworkspace.Workspace
import dev.mooner.starlightworkspace.WorkspaceException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Project files and logs as MCP resources (`starlight://projects/{project}/files/{path}`, `.../logs`), and prompts
 * that start common work. Clients attach resources without a tool call; Claude Code shows prompts as slash commands.
 */
class BuiltinContext(
    private val workspace: () -> Workspace,
) {

    // ------------------------------------------------------------------ resources

    /** Each project's main script and logs. */
    fun listResources(): JsonArray = buildJsonArray {
        for (project in workspace().projects()) {
            add(buildJsonObject {
                put("uri", fileUri(project.name, project.mainScript))
                put("name", "${project.name}/${project.mainScript}")
                put("description", "Main script of ${project.name}")
                put("mimeType", mimeTypeOf(project.mainScript))
            })
            add(buildJsonObject {
                put("uri", logsUri(project.name))
                put("name", "${project.name} logs")
                put("mimeType", "text/plain")
            })
        }
    }

    fun listResourceTemplates(): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("uriTemplate", "$SCHEME{project}/files/{+path}")
            put("name", "Project file")
            put("description", "A text file of a StarLight project; path is relative to the project directory.")
        })
        add(buildJsonObject {
            put("uriTemplate", "$SCHEME{project}/logs")
            put("name", "Project logs")
            put("description", "The latest logs of a StarLight project.")
            put("mimeType", "text/plain")
        })
    }

    fun readResource(uri: String): JsonArray {
        val rest = uri.removePrefix(SCHEME).takeIf { it != uri } ?: throw notFound(uri)
        val projectName = decode(rest.substringBefore('/'))
        val target = rest.substringAfter('/', "")
        val project = try {
            workspace().project(projectName)
        } catch (e: WorkspaceException) {
            throw notFound(uri)
        }
        val (mimeType, text) = when {
            target == LOGS -> "text/plain" to project.logs(0).takeLast(LOG_LIMIT).joinToString("\n") { "${it.type} [${it.tag}] ${it.message}" }
            target.startsWith(FILES) -> {
                val path = target.removePrefix(FILES).split('/').joinToString("/", transform = ::decode)
                val file = try {
                    project.readFile(path)
                } catch (e: WorkspaceException) {
                    throw notFound(uri)
                }
                mimeTypeOf(path) to file.text
            }
            else -> throw notFound(uri)
        }
        return buildJsonArray {
            add(buildJsonObject {
                put("uri", uri)
                put("mimeType", mimeType)
                put("text", text)
            })
        }
    }

    // ------------------------------------------------------------------ prompts

    fun listPrompts(): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("name", PROMPT_DEBUG)
            put("title", "Debug a project")
            put("description", "Starts debugging a StarLight project with its script, events and latest logs.")
            putJsonArray("arguments") {
                add(argument("project", "Name of the project", required = true))
                add(argument("problem", "What goes wrong", required = false))
            }
        })
        add(buildJsonObject {
            put("name", PROMPT_NEW_BOT)
            put("title", "Make a new bot")
            put("description", "Creates a StarLight project for a bot you describe, then writes, compiles and tries it.")
            putJsonArray("arguments") {
                add(argument("description", "What the bot should do", required = true))
                add(argument("name", "Project name", required = false))
            }
        })
    }

    fun getPrompt(name: String, arguments: Map<String, String>): JsonObject {
        val (description, text) = when (name) {
            PROMPT_DEBUG   -> "Debug a StarLight project" to debugPrompt(arguments)
            PROMPT_NEW_BOT -> "Make a new StarLight bot" to newBotPrompt(arguments)
            else           -> throw JsonRpcException(JsonRpc.INVALID_PARAMS, "Unknown prompt: $name")
        }
        return buildJsonObject {
            put("description", description)
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonObject("content") {
                        put("type", "text")
                        put("text", text)
                    }
                })
            }
        }
    }

    private fun debugPrompt(arguments: Map<String, String>): String {
        val name = arguments["project"] ?: throw JsonRpcException(JsonRpc.INVALID_PARAMS, "Missing argument: project")
        val project = try {
            workspace().project(name)
        } catch (e: WorkspaceException) {
            throw JsonRpcException(JsonRpc.INVALID_PARAMS, e.message.orEmpty())
        }
        val summary = project.summary
        val script = runCatching { project.readFile(summary.mainScript).text }.getOrElse { "(could not read: ${it.message})" }
        val logs = project.logs(0).takeLast(LOG_LIMIT).joinToString("\n") { "${it.type} [${it.tag}] ${it.message}" }.ifEmpty { "(no logs)" }
        return buildString {
            appendLine("Help me debug the StarLight project \"${summary.name}\" (${summary.languageId}, ${if (summary.isEnabled) "on" else "off"}, ${if (summary.isCompiled) "compiled" else "not compiled"}).")
            arguments["problem"]?.takeIf { it.isNotBlank() }?.let { appendLine("The problem: $it") }
            appendLine()
            appendLine("Events it receives: ${project.allowedEvents().sorted().joinToString().ifEmpty { "none" }}")
            appendLine()
            appendLine("Main script ${summary.mainScript}:")
            appendLine("```")
            appendLine(script)
            appendLine("```")
            appendLine()
            appendLine("Latest logs:")
            appendLine("```")
            appendLine(logs)
            appendLine("```")
            appendLine()
            append("Find the cause, fix it with ${tool("edit_file")} or ${tool("write_file")}, compile with ${tool("compile_project")}, ")
            append("and check it with ${tool("send_debug_message")} if the bot answers messages.")
        }
    }

    private fun newBotPrompt(arguments: Map<String, String>): String {
        val description = arguments["description"] ?: throw JsonRpcException(JsonRpc.INVALID_PARAMS, "Missing argument: description")
        return buildString {
            appendLine("Make a StarLight chatbot project that does this: $description")
            arguments["name"]?.takeIf { it.isNotBlank() }?.let { appendLine("Name the project \"$it\".") }
            appendLine()
            appendLine("1. See the languages with ${tool("list_languages")} (prefer NeonJS) and the events with ${tool("list_events")}.")
            appendLine("2. Create it with ${tool("create_project")}, then read the generated main script.")
            appendLine("3. Write the bot with ${tool("write_file")}. In NeonJS, event functions such as onMessage must be exported.")
            appendLine("4. Compile with ${tool("compile_project")} and fix errors until it compiles.")
            appendLine("5. Turn it on with ${tool("set_project_enabled")} and try it with ${tool("send_debug_message")}.")
        }
    }

    private fun argument(name: String, description: String, required: Boolean): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        put("required", required)
    }

    private fun tool(name: String): String =
        McpServer.RESERVED_PREFIX + name

    private fun notFound(uri: String): JsonRpcException =
        JsonRpcException(RESOURCE_NOT_FOUND, "Resource not found: $uri")

    private companion object {
        const val SCHEME = "starlight://projects/"
        const val FILES = "files/"
        const val LOGS = "logs"
        const val LOG_LIMIT = 100

        const val PROMPT_DEBUG = "debug_project"
        const val PROMPT_NEW_BOT = "new_bot"

        /** The MCP error for a resource that doesn't exist. */
        const val RESOURCE_NOT_FOUND = -32002

        fun fileUri(project: String, path: String): String =
            SCHEME + encode(project) + "/" + FILES + path.split('/').joinToString("/", transform = ::encode)

        fun logsUri(project: String): String =
            SCHEME + encode(project) + "/" + LOGS

        fun encode(segment: String): String =
            URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")

        fun decode(segment: String): String =
            URLDecoder.decode(segment, Charsets.UTF_8.name())

        fun mimeTypeOf(path: String): String =
            when (path.substringAfterLast('.', "").lowercase()) {
                "js", "mjs", "cjs" -> "text/javascript"
                "ts"               -> "text/typescript"
                "json"             -> "application/json"
                "md"               -> "text/markdown"
                else               -> "text/plain"
            }
    }
}
