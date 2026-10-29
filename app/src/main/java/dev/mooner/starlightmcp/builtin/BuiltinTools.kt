package dev.mooner.starlightmcp.builtin

import dev.mooner.starlightmcp.protocol.BuiltinTool
import dev.mooner.starlightmcp.protocol.booleanArg
import dev.mooner.starlightmcp.protocol.intArg
import dev.mooner.starlightmcp.protocol.requireString
import dev.mooner.starlightmcp.protocol.stringArg
import dev.mooner.starlightmcp.tool.McpServer
import dev.mooner.starlightmcp.tool.ToolResult
import dev.mooner.starlightmcp.tool.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** How much the built-in tools may do. */
enum class BuiltinAccess {
    OFF,
    READ_ONLY,
    /** Also writes files, compiles, turns projects on and off and sends debug room messages. */
    READ_WRITE,
}

/** Tools for working on StarLight projects from an MCP client. */
class BuiltinTools(
    private val host: StarlightHost,
) {

    private val readTools: List<BuiltinTool> = listOf(
        tool(
            "list_projects",
            "Lists the StarLight projects (chatbot scripts) on this device with their language, main script and state.",
            readOnly = true,
        ) { _ ->
            val projects = buildJsonArray {
                for (project in host.projects())
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
                for (language in host.languages())
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
            val project = findProject(args)
            val dir = resolve(project, args.stringArg("path") ?: ".")
            require(dir.isDirectory) { "Not a directory: ${args.stringArg("path")}" }
            val root = project.directory.canonicalFile
            val lines = dir.walkTopDown()
                .filter { it != dir }
                .take(MAX_LISTED_FILES + 1)
                .map { file ->
                    val path = file.relativeTo(root).invariantSeparatorsPath
                    if (file.isDirectory) "$path/" else "$path (${file.length()} bytes)"
                }
                .toList()
            val text = lines.take(MAX_LISTED_FILES).joinToString("\n").ifEmpty { "(empty)" }
            ToolResult.text(if (lines.size > MAX_LISTED_FILES) "$text\n... (more than $MAX_LISTED_FILES files)" else text)
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
            val file = resolve(project, args.stringArg("path") ?: project.mainScript)
            require(file.isFile) { "No such file: ${file.relativeTo(project.directory.canonicalFile).invariantSeparatorsPath}" }
            require(file.length() <= MAX_READ_BYTES) { "File is larger than ${MAX_READ_BYTES / 1024}KB" }
            ToolResult.text(file.readText())
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
            val project = findProject(args)
            ToolResult.text(formatLogs(project.logs(), args.intArg("limit") ?: DEFAULT_LOG_LIMIT))
        },
        tool(
            "get_app_logs",
            "Returns the latest logs of StarLight itself and its plugins.",
            readOnly = true,
            params = {
                integer("limit", "Number of latest entries to return (default $DEFAULT_LOG_LIMIT).", optional = true)
            },
        ) { args ->
            ToolResult.text(formatLogs(host.appLogs(), args.intArg("limit") ?: DEFAULT_LOG_LIMIT))
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
            },
        ) { args ->
            val project = findProject(args)
            val path = args.requireString("path")
            val content = args.requireString("content")
            val file = resolve(project, path)
            require(!file.isDirectory) { "Is a directory: $path" }
            file.parentFile?.mkdirs()
            file.writeText(content)
            ToolResult.text("Wrote ${content.toByteArray().size} bytes to $path")
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
            val project = findProject(args)
            val path = args.requireString("path")
            val file = resolve(project, path)
            require(file.isFile) { "No such file: $path" }
            require(file.delete()) { "Could not delete $path" }
            ToolResult.text("Deleted $path")
        },
        tool(
            "compile_project",
            "Compiles a project, which applies changes to its scripts, and returns the logs written while compiling.",
            params = { project() },
        ) { args ->
            val project = findProject(args)
            val startMillis = System.currentTimeMillis()
            val error = try {
                project.compile()
                null
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
            val logs = project.logs().filter { it.millis >= startMillis }
            val logText = if (logs.isEmpty()) "" else "\n\nLogs:\n" + formatLogs(logs, logs.size)
            if (error == null)
                ToolResult.text("Compiled ${project.name}$logText")
            else
                ToolResult.error("Failed to compile ${project.name}: $error$logText")
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
            project.setEnabled(enabled)
            ToolResult.text("${project.name} is now ${if (project.isEnabled) "on" else "off"}")
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
            val project = findProject(args)
            val message = DebugMessage(
                text = args.requireString("message"),
                sender = args.stringArg("sender"),
                room = args.stringArg("room"),
                isGroupChat = args.booleanArg("isGroupChat"),
            )
            val waitMillis = (args.intArg("waitSeconds") ?: DEFAULT_REPLY_WAIT_SECONDS).coerceIn(1, MAX_REPLY_WAIT_SECONDS) * 1000L
            sendDebugMessage(project, message, waitMillis)
        },
    )

    /**
     * Waits for the bot's replies until [waitMillis] passes, or until it has been quiet for a moment after
     * replying or failing, since a bot may send several messages.
     */
    private suspend fun sendDebugMessage(project: ProjectHandle, message: DebugMessage, waitMillis: Long): ToolResult {
        val events = Channel<Any>(Channel.UNLIMITED)
        val startMillis = System.currentTimeMillis()
        project.sendDebugMessage(message, onReply = { events.trySend(it) }, onError = { events.trySend(it) })

        val replies = arrayListOf<String>()
        var error: Throwable? = null
        val deadline = startMillis + waitMillis
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0)
                break
            val isAnswered = replies.isNotEmpty() || error != null
            val event = withTimeoutOrNull(if (isAnswered) minOf(remaining, REPLY_QUIET_MILLIS) else remaining) { events.receive() }
                ?: break
            when (event) {
                is String    -> replies += event
                is Throwable -> error = event
            }
        }
        events.close()

        val logs = project.logs().filter { it.millis >= startMillis }
        val result = buildJsonObject {
            putJsonArray("replies") { replies.forEach { add(it) } }
            error?.let { put("error", it.message ?: it.toString()) }
            if (replies.isEmpty() && error == null)
                put("note", "No reply within ${waitMillis / 1000}s")
            if (logs.isNotEmpty())
                put("logs", formatLogs(logs, logs.size))
        }
        return ToolResult.text(result.toString(), isError = error != null)
    }

    fun tools(access: BuiltinAccess): List<BuiltinTool> =
        when (access) {
            BuiltinAccess.OFF        -> emptyList()
            BuiltinAccess.READ_ONLY  -> readTools
            BuiltinAccess.READ_WRITE -> readTools + writeTools
        }

    private fun findProject(args: JsonObject): ProjectHandle {
        val key = args.requireString("project")
        val projects = host.projects()
        return projects.firstOrNull { it.name == key }
            ?: projects.firstOrNull { it.id == key }
            ?: projects.firstOrNull { it.name.equals(key, ignoreCase = true) }
            ?: throw IllegalArgumentException("No project named '$key'. Use ${McpServer.RESERVED_PREFIX}list_projects to see the projects.")
    }

    /** [path] inside the project directory; anything leading outside of it is refused. */
    private fun resolve(project: ProjectHandle, path: String): File {
        val root = project.directory.canonicalFile
        require(!File(path).isAbsolute) { "Path must be relative to the project directory: $path" }
        val file = root.resolve(path).canonicalFile
        require(file == root || file.path.startsWith(root.path + File.separator)) { "Path is outside the project directory: $path" }
        return file
    }

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

        private fun add(name: String, type: String, description: String, optional: Boolean) {
            properties += name to {
                put("type", type)
                put("description", description)
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
        const val MAX_LISTED_FILES = 500
        const val MAX_READ_BYTES = 1024L * 1024
        const val DEFAULT_LOG_LIMIT = 50
        const val MAX_LOG_LIMIT = 500
        const val DEFAULT_REPLY_WAIT_SECONDS = 5
        const val MAX_REPLY_WAIT_SECONDS = 60
        const val REPLY_QUIET_MILLIS = 1_000L

        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    }
}
