package dev.mooner.starlightmcp.builtin

import dev.mooner.starlightmcp.protocol.BuiltinTool
import dev.mooner.starlightmcp.protocol.booleanArg
import dev.mooner.starlightmcp.protocol.intArg
import dev.mooner.starlightmcp.protocol.requireString
import dev.mooner.starlightmcp.protocol.stringArg
import dev.mooner.starlightmcp.tool.McpServer
import dev.mooner.starlightmcp.tool.ToolResult
import dev.mooner.starlightmcp.tool.ToolSpec
import dev.mooner.starlightworkspace.DebugMessage
import dev.mooner.starlightworkspace.LogEntry
import dev.mooner.starlightworkspace.Workspace
import dev.mooner.starlightworkspace.WorkspaceException
import dev.mooner.starlightworkspace.WorkspaceProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.seconds

/** How much the built-in tools may do. */
enum class BuiltinAccess {
    OFF,
    READ_ONLY,
    /** Also writes files, compiles, turns projects on and off and sends debug room messages. */
    READ_WRITE,
}

/** Tools for working on StarLight projects from an MCP client, over the shared [Workspace]. */
class BuiltinTools(
    private val workspace: () -> Workspace,
) {

    private val readTools: List<BuiltinTool> = listOf(
        tool(
            "list_projects",
            "Lists the StarLight projects (chatbot scripts) on this device with their language, main script and state.",
            readOnly = true,
        ) { _ ->
            val projects = buildJsonArray {
                for (project in workspace().projects())
                    add(buildJsonObject {
                        put("name", project.name)
                        put("id", project.id)
                        put("language", project.languageId)
                        put("mainScript", project.mainScript)
                        put("enabled", project.isEnabled)
                        put("compiled", project.isCompiled)
                        put("activeJobs", project.activeJobs)
                    })
            }
            ToolResult.text(projects.toString())
        },
        tool(
            "list_languages",
            "Lists the script languages installed in StarLight.",
            readOnly = true,
        ) { _ ->
            val languages = buildJsonArray {
                for (language in workspace().languages())
                    add(buildJsonObject {
                        put("id", language.id)
                        put("name", language.name)
                        put("fileExtension", language.fileExtension)
                    })
            }
            ToolResult.text(languages.toString())
        },
        tool(
            "list_files",
            "Lists files of a project, recursively. Paths are relative to the project directory.",
            readOnly = true,
            params = {
                project()
                string("path", "Directory to list, relative to the project directory. Defaults to the project directory.", optional = true)
            },
        ) { args ->
            val files = findProject(args).listFiles(args.stringArg("path") ?: ".", MAX_LISTED_FILES)
            val text = files.entries
                .joinToString("\n") { if (it.isDirectory) "${it.path}/" else "${it.path} (${it.size} bytes)" }
                .ifEmpty { "(empty)" }
            ToolResult.text(if (files.isTruncated) "$text\n... (more than $MAX_LISTED_FILES files)" else text)
        },
        tool(
            "read_file",
            "Reads a text file of a project. Without a path, reads the project's main script.",
            readOnly = true,
            params = {
                project()
                string("path", "File path relative to the project directory. Defaults to the main script.", optional = true)
            },
        ) { args ->
            val project = findProject(args)
            val file = project.readFile(args.stringArg("path") ?: project.summary.mainScript)
            withStructured(ToolResult.text(file.text), fileJson(file.path, file.hash))
        },
        tool(
            "search_files",
            "Searches the text files of a project and returns matching lines as path:line: text.",
            readOnly = true,
            params = {
                project()
                string("query", "Text to find, or a regular expression if regex is true.")
                boolean("regex", "Whether query is a regular expression (default false).", optional = true)
                boolean("ignoreCase", "Whether to ignore case (default true).", optional = true)
                integer("limit", "Maximum number of matches (default $DEFAULT_SEARCH_LIMIT).", optional = true)
            },
        ) { args ->
            val hits = findProject(args).search(
                query = args.requireString("query"),
                isRegex = args.booleanArg("regex") ?: false,
                ignoreCase = args.booleanArg("ignoreCase") ?: true,
                limit = (args.intArg("limit") ?: DEFAULT_SEARCH_LIMIT).coerceIn(1, MAX_SEARCH_LIMIT),
            )
            val text = hits.joinToString("\n") { "${it.path}:${it.line}: ${it.text}" }.ifEmpty { "(no matches)" }
            withStructured(ToolResult.text(text), buildJsonObject {
                putJsonArray("matches") {
                    for (hit in hits)
                        add(buildJsonObject {
                            put("path", hit.path)
                            put("line", hit.line)
                            put("text", hit.text)
                        })
                }
            })
        },
        tool(
            "list_events",
            "Lists the events projects can receive, with the function a script defines for each (such as onMessage) " +
                "and its arguments. With a project, also tells which events it receives.",
            readOnly = true,
            params = {
                string("project", "Name (or id) of a project, to see which events it receives.", optional = true)
            },
        ) { args ->
            val allowed = args.stringArg("project")?.let { findProject(args).allowedEvents() }
            val events = buildJsonArray {
                for (event in workspace().eventCatalog())
                    add(buildJsonObject {
                        put("id", event.id)
                        put("name", event.name)
                        put("function", event.functionName)
                        putJsonArray("args") { event.args.forEach { add("${it.name}: ${it.type}") } }
                        event.comment?.let { put("comment", it) }
                        allowed?.let { put("allowed", event.id in it) }
                    })
            }
            jsonResult(buildJsonObject { put("events", events) })
        },
        tool(
            "list_apis",
            "Lists the globals StarLight gives scripts, with their functions and values.",
            readOnly = true,
        ) { _ ->
            val apis = buildJsonArray {
                for (api in workspace().apiCatalog())
                    add(buildJsonObject {
                        put("name", api.name)
                        put("class", api.instanceClass)
                        putJsonArray("functions") {
                            api.functions.forEach { add("${it.name}(${it.args.joinToString()}): ${it.returns}") }
                        }
                        putJsonArray("values") { api.values.forEach { add("${it.name}: ${it.type}") } }
                    })
            }
            jsonResult(buildJsonObject { put("apis", apis) })
        },
        tool(
            "get_project_logs",
            "Returns the latest logs of a project: compile results, errors and what the script logged.",
            readOnly = true,
            params = {
                project()
                integer("limit", "Number of latest entries to return (default $DEFAULT_LOG_LIMIT).", optional = true)
            },
        ) { args ->
            ToolResult.text(formatLogs(findProject(args).logs(0), args.intArg("limit") ?: DEFAULT_LOG_LIMIT))
        },
        tool(
            "get_app_logs",
            "Returns the latest logs of StarLight itself and its plugins.",
            readOnly = true,
            params = {
                integer("limit", "Number of latest entries to return (default $DEFAULT_LOG_LIMIT).", optional = true)
            },
        ) { args ->
            ToolResult.text(formatLogs(workspace().appLogs(0), args.intArg("limit") ?: DEFAULT_LOG_LIMIT))
        },
    )

    private val writeTools: List<BuiltinTool> = listOf(
        tool(
            "write_file",
            "Creates or overwrites a text file of a project. Compile the project afterwards to apply script changes.",
            destructive = true,
            params = {
                project()
                string("path", "File path relative to the project directory.")
                string("content", "The whole new content of the file.")
                expectedHash()
            },
        ) { args ->
            val path = args.requireString("path")
            val content = args.requireString("content")
            val file = findProject(args).writeFile(path, content, args.stringArg("expectedHash"), ACTOR)
            withStructured(ToolResult.text("Wrote ${content.toByteArray().size} bytes to $path"), fileJson(file.path, file.hash))
        },
        tool(
            "edit_file",
            "Replaces a piece of text in a file of a project. The text to replace must occur exactly once; include " +
                "enough surrounding lines to make it unique. Cheaper than rewriting the whole file.",
            destructive = true,
            params = {
                project()
                string("path", "File path relative to the project directory.")
                string("old", "The exact text to replace.")
                string("new", "The text to put in its place.")
                expectedHash()
            },
        ) { args ->
            val path = args.requireString("path")
            val file = findProject(args).editFile(path, args.requireString("old"), args.requireString("new"), args.stringArg("expectedHash"), ACTOR)
            withStructured(ToolResult.text("Edited $path"), fileJson(file.path, file.hash))
        },
        tool(
            "rename_file",
            "Renames or moves a file within a project.",
            destructive = true,
            params = {
                project()
                string("from", "Current path, relative to the project directory.")
                string("to", "New path, relative to the project directory.")
            },
        ) { args ->
            val from = args.requireString("from")
            val to = args.requireString("to")
            findProject(args).renameFile(from, to, ACTOR)
            ToolResult.text("Renamed $from to $to")
        },
        tool(
            "delete_file",
            "Deletes a file of a project. Directories are not deleted.",
            destructive = true,
            params = {
                project()
                string("path", "File path relative to the project directory.")
            },
        ) { args ->
            val path = args.requireString("path")
            findProject(args).deleteFile(path, ACTOR)
            ToolResult.text("Deleted $path")
        },
        tool(
            "compile_project",
            "Compiles a project, which applies changes to its scripts, and returns the logs written while compiling.",
            params = { project() },
        ) { args ->
            val project = findProject(args)
            val result = project.compile(ACTOR)
            val logText = if (result.logs.isEmpty()) "" else "\n\nLogs:\n" + formatLogs(result.logs, result.logs.size)
            val location = result.errorLine?.let { line ->
                " (at ${result.errorPath ?: project.summary.mainScript}:$line${result.errorColumn?.let { ":$it" }.orEmpty()})"
            }.orEmpty()
            val text = if (result.isSuccess)
                "Compiled ${project.summary.name}$logText"
            else
                "Failed to compile ${project.summary.name}: ${result.error}$location$logText"
            withStructured(ToolResult.text(text, isError = !result.isSuccess), buildJsonObject {
                put("success", result.isSuccess)
                result.error?.let { put("error", it) }
                result.errorPath?.let { put("errorPath", it) }
                result.errorLine?.let { put("errorLine", it) }
                result.errorColumn?.let { put("errorColumn", it) }
            })
        },
        tool(
            "set_project_events",
            "Chooses which events a project receives. A project gets messages only if it receives " +
                "${MESSAGE_EVENT}; see ${McpServer.RESERVED_PREFIX}list_events for the ids.",
            params = {
                project()
                stringArray("allow", "Event ids to start receiving.", optional = true)
                stringArray("disallow", "Event ids to stop receiving.", optional = true)
            },
        ) { args ->
            val events = findProject(args).updateEvents(args.stringSet("allow"), args.stringSet("disallow"), ACTOR)
            jsonResult(buildJsonObject { putJsonArray("allowed") { events.sorted().forEach { add(it) } } })
        },
        tool(
            "create_project",
            "Creates a project with the language's starting code for the chosen events. It starts turned off and " +
                "not compiled; write its script, compile it and turn it on.",
            params = {
                string("name", "Project name: letters, digits, Hangul, '-' and '_'.")
                string("language", "Language id from ${McpServer.RESERVED_PREFIX}list_languages.")
                stringArray("events", "Event ids it receives (default: ${MESSAGE_EVENT}).", optional = true)
            },
        ) { args ->
            val events = args.stringSet("events").ifEmpty { setOf(MESSAGE_EVENT) }
            val project = workspace().createProject(args.requireString("name"), args.requireString("language"), events, ACTOR)
            val summary = project.summary
            ToolResult.text("Created ${summary.name} with main script ${summary.mainScript}")
        },
        tool(
            "set_project_enabled",
            "Turns a project on or off. A project that is off doesn't receive events.",
            params = {
                project()
                boolean("enabled", "true to turn the project on, false to turn it off.")
            },
        ) { args ->
            val project = findProject(args)
            val enabled = args.booleanArg("enabled") ?: throw IllegalArgumentException("Missing argument: enabled")
            project.setEnabled(enabled, ACTOR)
            ToolResult.text("${project.summary.name} is now ${if (project.summary.isEnabled) "on" else "off"}")
        },
        tool(
            "send_debug_message",
            "Sends a chat message to a project from its debug room, as if a user sent it, and returns what the bot " +
                "replied to the room. The project must be compiled and receive the message event (onMessage). " +
                "Sender, room and group chat default to StarLight's debug room settings.",
            params = {
                project()
                string("message", "The message text.")
                string("sender", "Name of the sender.", optional = true)
                string("room", "Name of the chat room.", optional = true)
                boolean("isGroupChat", "Whether the room is a group chat.", optional = true)
                integer("waitSeconds", "How long to wait for replies (default $DEFAULT_REPLY_WAIT_SECONDS, at most $MAX_REPLY_WAIT_SECONDS).", optional = true)
            },
        ) { args ->
            val message = DebugMessage(
                text = args.requireString("message"),
                sender = args.stringArg("sender"),
                room = args.stringArg("room"),
                isGroupChat = args.booleanArg("isGroupChat"),
            )
            val wait = (args.intArg("waitSeconds") ?: DEFAULT_REPLY_WAIT_SECONDS).coerceIn(1, MAX_REPLY_WAIT_SECONDS).seconds
            val reply = findProject(args).sendDebugMessage(message, wait, ACTOR)
            val result = buildJsonObject {
                putJsonArray("replies") { reply.replies.forEach { add(it) } }
                reply.error?.let { put("error", it) }
                if (reply.replies.isEmpty() && reply.error == null)
                    put("note", "No reply within ${wait.inWholeSeconds}s")
                if (reply.logs.isNotEmpty())
                    put("logs", formatLogs(reply.logs, reply.logs.size))
            }
            ToolResult.text(result.toString(), isError = reply.error != null)
        },
    )

    fun tools(access: BuiltinAccess): List<BuiltinTool> =
        when (access) {
            BuiltinAccess.OFF        -> emptyList()
            BuiltinAccess.READ_ONLY  -> readTools
            BuiltinAccess.READ_WRITE -> readTools + writeTools
        }

    private fun findProject(args: JsonObject): WorkspaceProject {
        val key = args.requireString("project")
        return try {
            workspace().project(key)
        } catch (e: WorkspaceException) {
            throw WorkspaceException("${e.message}. Use ${McpServer.RESERVED_PREFIX}list_projects to see the projects.")
        }
    }

    private fun fileJson(path: String, hash: String): JsonObject = buildJsonObject {
        put("path", path)
        put("hash", hash)
    }

    private fun withStructured(result: ToolResult, structured: JsonObject): ToolResult =
        ToolResult(result.content, result.isError, structured)

    /** JSON both as the text models read and as structured content. */
    private fun jsonResult(json: JsonObject): ToolResult =
        withStructured(ToolResult.text(json.toString()), json)

    private fun JsonObject.stringSet(name: String): Set<String> =
        (get(name) as? JsonArray)?.mapNotNullTo(linkedSetOf()) { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }.orEmpty()

    private fun formatLogs(logs: List<LogEntry>, limit: Int): String =
        logs.takeLast(limit.coerceIn(1, MAX_LOG_LIMIT))
            .joinToString("\n") { "${TIME_FORMAT.format(Instant.ofEpochMilli(it.millis))} ${it.type} [${it.tag}] ${it.message}" }
            .ifEmpty { "(no logs)" }

    private fun tool(
        name: String,
        description: String,
        readOnly: Boolean = false,
        destructive: Boolean = false,
        params: ParamsBuilder.() -> Unit = {},
        run: suspend (JsonObject) -> ToolResult,
    ): BuiltinTool {
        val schema = ParamsBuilder().apply(params).build()
        val annotations = buildJsonObject {
            put("readOnlyHint", readOnly)
            if (!readOnly)
                put("destructiveHint", destructive)
            put("openWorldHint", false)
        }
        val spec = ToolSpec(McpServer.RESERVED_PREFIX + name, description, schema, annotations = annotations)
        // File and project work blocks, and compiling waits for the script engine.
        return BuiltinTool(spec) { args -> withContext(Dispatchers.IO) { run(args) } }
    }

    private class ParamsBuilder {

        private val properties: MutableList<Pair<String, JsonObjectBuilder.() -> Unit>> = arrayListOf()
        private val required: MutableList<String> = arrayListOf()

        fun project() =
            string("project", "Name (or id) of the project.")

        fun string(name: String, description: String, optional: Boolean = false) =
            add(name, "string", description, optional)

        fun integer(name: String, description: String, optional: Boolean = false) =
            add(name, "integer", description, optional)

        fun boolean(name: String, description: String, optional: Boolean = false) =
            add(name, "boolean", description, optional)

        fun stringArray(name: String, description: String, optional: Boolean = false) =
            add(name, "array", description, optional) { putJsonObject("items") { put("type", "string") } }

        fun expectedHash() =
            string("expectedHash", "Hash from reading the file; if the file changed since, nothing is written. " +
                "An empty string writes only if the file doesn't exist.", optional = true)

        private fun add(name: String, type: String, description: String, optional: Boolean, extra: JsonObjectBuilder.() -> Unit = {}) {
            properties += name to {
                put("type", type)
                put("description", description)
                extra()
            }
            if (!optional)
                required += name
        }

        fun build(): JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                for ((name, schema) in properties)
                    putJsonObject(name, schema)
            }
            if (required.isNotEmpty())
                putJsonArray("required") { required.forEach { add(it) } }
        }
    }

    private companion object {
        /** The actor of the workspace changes made through MCP. */
        const val ACTOR = "mcp"

        /** StarLight's message event, which new projects receive by default as in its new project dialog. */
        const val MESSAGE_EVENT = "starlight.message.create"

        const val MAX_LISTED_FILES = 500
        const val DEFAULT_LOG_LIMIT = 50
        const val MAX_LOG_LIMIT = 500
        const val DEFAULT_REPLY_WAIT_SECONDS = 5
        const val MAX_REPLY_WAIT_SECONDS = 60
        const val DEFAULT_SEARCH_LIMIT = 100
        const val MAX_SEARCH_LIMIT = 1000

        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    }
}
