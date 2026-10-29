package dev.mooner.starlightmcp.protocol

import dev.mooner.starlightmcp.FakeHost
import dev.mooner.starlightmcp.FakeOwner
import dev.mooner.starlightmcp.FakeProject
import dev.mooner.starlightmcp.builtin.BuiltinAccess
import dev.mooner.starlightmcp.builtin.BuiltinTools
import dev.mooner.starlightmcp.builtin.LogEntry
import dev.mooner.starlightmcp.server.ServerInfo
import dev.mooner.starlightmcp.tool.ProjectToolsets
import dev.mooner.starlightmcp.tool.ScriptToolRunner
import dev.mooner.starlightmcp.tool.ToolHandler
import dev.mooner.starlightmcp.tool.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class McpProtocolTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val registry = ToolRegistry()
    private val toolsets = ProjectToolsets(registry) { ServerInfo(true, 8765, emptyList()) }
    private val host = FakeHost()
    private var access = BuiltinAccess.READ_WRITE
    private lateinit var project: FakeProject
    private lateinit var protocol: McpProtocol

    @Before
    fun setUp() {
        val dir = folder.newFolder("bot")
        dir.resolve("index.js").writeText("// main")
        dir.resolve("lib").mkdirs()
        dir.resolve("lib/util.js").writeText("// util")
        folder.root.resolve("secret.txt").writeText("secret")
        project = FakeProject("bot", dir)
        host.projects += project

        val builtins = BuiltinTools(host)
        protocol = McpProtocol(
            serverName = "starlight",
            serverVersion = "0.1.0",
            instructions = "hi",
            builtins = { builtins.tools(access) },
            registry = registry,
            runner = ScriptToolRunner(scope) { 1_000 },
        )
        toolsets.claim(FakeOwner("bot")).tool("echo", "Echoes", ToolHandler { args -> (args as Map<*, *>)["text"] })
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun initializeNegotiatesTheVersion() {
        val result = request("initialize", """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}""")
        assertEquals("2025-06-18", result["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("starlight", result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue("tools" in result["capabilities"]!!.jsonObject)

        val unknown = request("initialize", """{"protocolVersion":"1999-01-01"}""")
        assertEquals(McpProtocol.SUPPORTED_VERSIONS.first(), unknown["protocolVersion"]!!.jsonPrimitive.content)
    }

    @Test
    fun notificationsGetNoResponse() {
        assertNull(handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        assertNull(handle("""{"jsonrpc":"2.0","id":5,"result":{}}"""))
    }

    @Test
    fun errors() {
        assertEquals(JsonRpc.METHOD_NOT_FOUND, errorCode(handle("""{"jsonrpc":"2.0","id":1,"method":"nope"}""")))
        assertEquals(JsonRpc.INVALID_REQUEST, errorCode(handle("""{"id":1,"method":"ping"}""")))
        assertEquals(JsonRpc.INVALID_PARAMS, errorCode(handle("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"missing"}}""")))
        // The id is echoed as sent.
        assertEquals("\"abc\"", handle("""{"jsonrpc":"2.0","id":"abc","method":"ping"}""")!!["id"].toString())
    }

    @Test
    fun listsBuiltinsThenProjectTools() {
        val names = toolNames()
        assertEquals("starlight_list_projects", names.first())
        assertTrue("starlight_write_file" in names)
        assertEquals("echo", names.last())

        access = BuiltinAccess.READ_ONLY
        assertFalse("starlight_write_file" in toolNames())
        assertTrue("starlight_read_file" in toolNames())

        access = BuiltinAccess.OFF
        assertEquals(listOf("echo"), toolNames())
    }

    @Test
    fun callsProjectTools() {
        val result = call("echo", """{"text":"안녕"}""")
        assertEquals("안녕", text(result))
        assertFalse("isError" in result)
    }

    @Test
    fun readsAndWritesProjectFiles() {
        assertEquals("// main", text(call("starlight_read_file", """{"project":"bot"}""")))
        assertEquals("// util", text(call("starlight_read_file", """{"project":"id-bot","path":"lib/util.js"}""")))
        assertTrue(text(call("starlight_list_files", """{"project":"bot"}""")).contains("lib/util.js (7 bytes)"))

        call("starlight_write_file", """{"project":"bot","path":"new/dir/a.js","content":"x = 1"}""")
        assertEquals("x = 1", project.directory.resolve("new/dir/a.js").readText())

        call("starlight_delete_file", """{"project":"bot","path":"new/dir/a.js"}""")
        assertFalse(project.directory.resolve("new/dir/a.js").exists())
    }

    @Test
    fun staysInsideTheProjectDirectory() {
        for (path in listOf("../secret.txt", "lib/../../secret.txt", folder.root.resolve("secret.txt").path)) {
            val result = call("starlight_read_file", """{"project":"bot","path":${JsonPrimitive(path)}}""")
            assertTrue(path, result["isError"]!!.jsonPrimitive.content.toBoolean())
        }
        val write = call("starlight_write_file", """{"project":"bot","path":"../evil.js","content":"x"}""")
        assertTrue(write["isError"]!!.jsonPrimitive.content.toBoolean())
        assertFalse(folder.root.resolve("evil.js").exists())
    }

    @Test
    fun compilesAndReportsLogs() {
        val ok = call("starlight_compile_project", """{"project":"bot"}""")
        assertTrue(text(ok).startsWith("Compiled bot"))
        assertTrue(text(ok).contains("compiling"))
        assertTrue(project.isCompiled)

        project.compileError = "SyntaxError: line 3"
        val failed = call("starlight_compile_project", """{"project":"bot"}""")
        assertTrue(failed["isError"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(text(failed).contains("SyntaxError: line 3"))
    }

    @Test
    fun sendsDebugRoomMessages() {
        project.onMessage = { message, send ->
            send("${message.sender ?: "?"}@${message.room ?: "?"}: ${message.text}")
            Thread.sleep(100)
            send("second")
            project.logs += LogEntry("INFO", "bot", "handled", System.currentTimeMillis())
        }
        val result = call("starlight_send_debug_message", """{"project":"bot","message":"!ping","sender":"me","room":"r"}""")
        val json = Json.parseToJsonElement(text(result)).jsonObject
        assertEquals(listOf("me@r: !ping", "second"), json["replies"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(json["logs"]!!.jsonPrimitive.content.contains("handled"))
        assertFalse("isError" in result)
    }

    @Test
    fun debugRoomFailures() {
        project.onMessage = { _, _ -> throw IllegalStateException("ReferenceError: x") }
        val failed = call("starlight_send_debug_message", """{"project":"bot","message":"hi"}""")
        assertTrue(failed["isError"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(text(failed).contains("ReferenceError: x"))

        project.onMessage = { _, _ -> }
        val silent = call("starlight_send_debug_message", """{"project":"bot","message":"hi","waitSeconds":1}""")
        assertTrue(text(silent).contains("No reply within 1s"))

        project.onMessage = null
        val notAllowed = call("starlight_send_debug_message", """{"project":"bot","message":"hi"}""")
        assertTrue(text(notAllowed).contains("doesn't receive messages"))
    }

    @Test
    fun unknownProjectIsAToolError() {
        val result = call("starlight_read_file", """{"project":"nope"}""")
        assertTrue(result["isError"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(text(result).contains("starlight_list_projects"))
    }

    private fun toolNames(): List<String> =
        request("tools/list", "{}")["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }

    private fun call(name: String, arguments: String): JsonObject =
        request("tools/call", """{"name":"$name","arguments":$arguments}""")

    private fun text(result: JsonObject): String =
        (result["content"] as JsonArray).joinToString("") { it.jsonObject["text"]!!.jsonPrimitive.content }

    private fun request(method: String, params: String): JsonObject {
        val response = handle("""{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}""")!!
        val error = response["error"]
        if (error != null)
            throw AssertionError("$method failed: $error")
        return response["result"]!!.jsonObject
    }

    private fun handle(message: String): JsonObject? =
        runBlocking { protocol.handle(Json.parseToJsonElement(message)) }

    private fun errorCode(response: JsonObject?): Int =
        response!!["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt()
}
