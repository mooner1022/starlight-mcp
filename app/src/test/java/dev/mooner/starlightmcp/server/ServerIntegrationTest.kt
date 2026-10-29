package dev.mooner.starlightmcp.server

import dev.mooner.neonjs.HostAccess
import dev.mooner.neonjs.NeonContext
import dev.mooner.neonjs.NeonEngine
import dev.mooner.neonjs.SandboxPolicy
import dev.mooner.starlightmcp.FakeOwner
import dev.mooner.starlightmcp.protocol.McpProtocol
import dev.mooner.starlightmcp.tool.ProjectToolsets
import dev.mooner.starlightmcp.tool.ScriptToolRunner
import dev.mooner.starlightmcp.tool.ToolRegistry
import dev.mooner.starlightmcp.utils.sha256Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/** MCP over HTTP to tools written in NeonJS, set up the way StarLight sets up its engine. */
class ServerIntegrationTest {

    private val owner = FakeOwner("bot")
    private val registry = ToolRegistry()
    private val port = ServerSocket(0).use { it.localPort }
    private val toolsets = ProjectToolsets(registry) { ServerInfo(true, port, listOf("http://127.0.0.1:$port/mcp")) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logged = CopyOnWriteArrayList<String>()

    @Volatile
    private var settings = ServerSettings(tokenHash = sha256Hex(TOKEN), requireToken = true)
    @Volatile
    private var timeoutMillis = 2_000L

    private lateinit var engine: HttpServerEngine
    private lateinit var neon: NeonContext

    @Before
    fun setUp() {
        val protocol = McpProtocol(
            serverName = "starlight",
            serverVersion = "0.1.0",
            instructions = null,
            builtins = { emptyList() },
            registry = registry,
            runner = ScriptToolRunner(scope) { timeoutMillis },
        )
        engine = HttpServerEngine(McpHttpHandler(protocol, { settings }) { logged += it })
        engine.start("127.0.0.1", port)

        neon = NeonEngine.builder()
            .sandbox(SandboxPolicy.builder().exposeJavaGlobal(true).build())
            .hostAccess(
                HostAccess.builder(HostAccess.Level.ALL)
                    .defaultDenyList(false)
                    .allowLookup { true }
                    .build()
            )
            .webGlobals(true)
            .build()
            .newContext()
        neon["server"] = toolsets.claim(owner)
        neon.eval(SCRIPT, "test.js")
    }

    @After
    fun tearDown() {
        engine.stop()
        scope.cancel()
        neon.close()
    }

    @Test
    fun handshake() {
        val init = rpc("initialize", """{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"t","version":"1"}}""")
        assertEquals(200, init.status)
        assertTrue(init.header("Content-Type")!!.startsWith("application/json"))
        assertEquals("2025-11-25", init.result()["protocolVersion"]!!.jsonPrimitive.content)

        val initialized = post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        assertEquals(202, initialized.status)
        assertEquals("", initialized.body)
    }

    @Test
    fun listsScriptTools() {
        val tools = rpc("tools/list", "{}").result()["tools"]!!.jsonArray.associateBy { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(setOf("weather", "sum", "later", "boom", "never", "slow", "raw", "info"), tools.keys)
        assertJson(
            """{"type":"object","properties":{"city":{"type":"string"},"days":{"type":"integer"}},"required":["city"]}""",
            tools.getValue("weather").jsonObject["inputSchema"]!!,
        )
        assertEquals("Weather", tools.getValue("weather").jsonObject["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun scriptsReadArguments() {
        assertEquals("서울: 3 days", text(call("weather", """{"city":"서울","days":3}""")))
        assertEquals("서울: 1 days", text(call("weather", """{"city":"서울"}""")))
        assertJson(
            """{"total":6,"count":3,"first":1,"label":"x"}""",
            Json.parseToJsonElement(text(call("sum", """{"numbers":[1,2,3],"meta":{"label":"x"}}"""))),
        )
    }

    @Test
    fun asyncAndRawResults() {
        assertEquals("done", text(call("later", "{}")))
        val raw = call("raw", "{}")
        assertEquals("image", raw["content"]!!.jsonArray[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertJson("""{"n":1}""", raw["structuredContent"]!!)
    }

    @Test
    fun failuresAreToolErrors() {
        val boom = call("boom", "{}")
        assertTrue(boom["isError"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(text(boom).contains("kaboom"))
        assertTrue(owner.errors.any { "kaboom" in it })

        // NeonJS fails a promise that nothing can settle anymore right away.
        val never = call("never", "{}")
        assertTrue(never["isError"]!!.jsonPrimitive.content.toBoolean())

        timeoutMillis = 300
        val slow = call("slow", "{}")
        assertTrue(slow["isError"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(text(slow), text(slow).contains("timed out"))

        owner.isActive = false
        assertTrue(call("weather", """{"city":"a"}""")["isError"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun scriptsSeeTheServer() {
        assertJson("""{"running":true,"port":$port,"tools":8}""", Json.parseToJsonElement(text(call("info", "{}"))))
    }

    @Test
    fun accessToken() {
        assertEquals(401, post(PING, token = null).status)
        assertEquals(401, post(PING, token = "wrong").status)
        assertEquals(200, post(PING).status)
        assertEquals(200, post(PING, token = null, path = "/mcp?token=$TOKEN").status)

        // No token set: nothing gets in.
        settings = settings.copy(tokenHash = null)
        assertEquals(401, post(PING).status)

        settings = settings.copy(requireToken = false)
        assertEquals(200, post(PING, token = null).status)
    }

    @Test
    fun transportErrors() {
        assertEquals(405, send("GET", "/mcp").status)
        assertEquals("POST", send("GET", "/mcp").header("Allow"))
        assertEquals(404, send("POST", "/other", PING).status)

        val parse = post("{not json")
        assertEquals(400, parse.status)
        assertEquals(-32700, parse.json().jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())

        settings = settings.copy(maxBodyBytes = 16)
        assertEquals(413, post(PING + " ".repeat(20)).status)
    }

    @Test
    fun browserOrigins() {
        assertEquals(403, post(PING, headers = mapOf("Origin" to "https://evil.example")).status)
        assertEquals(200, post(PING, headers = mapOf("Origin" to "http://localhost:6274")).status)
    }

    @Test
    fun batches() {
        val response = post("""[$PING,{"jsonrpc":"2.0","method":"notifications/initialized"},{"jsonrpc":"2.0","id":2,"method":"tools/list"}]""")
        val responses = response.json().jsonArray
        assertEquals(listOf("1", "2"), responses.map { it.jsonObject["id"].toString() })
    }

    private class Response(val status: Int, private val headers: Map<String, List<String>>, val body: String) {

        fun header(name: String): String? =
            headers.entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

        fun json(): JsonElement =
            Json.parseToJsonElement(body)

        fun result(): JsonObject {
            val json = json().jsonObject
            json["error"]?.let { throw AssertionError("Request failed: $it") }
            return json["result"]!!.jsonObject
        }
    }

    @Test
    fun requestCutOffQuietly() {
        cutOffUpload("/mcp", mapOf("Authorization" to "Bearer $TOKEN"))
        Thread.sleep(500)
        assertEquals(emptyList<String>(), logged)
    }

    /** Sends [headers] and a part of the body it announces, then drops the connection. */
    private fun cutOffUpload(path: String, headers: Map<String, String>) {
        Socket("127.0.0.1", port).use { socket ->
            val head = buildString {
                append("POST $path HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nContent-Length: 100000\r\n")
                headers.forEach { (name, value) -> append("$name: $value\r\n") }
                append("\r\n{\"text\":")
            }
            socket.getOutputStream().apply { write(head.toByteArray()); flush() }
            Thread.sleep(200)
            // Reset, not a polite close, the way a dropped network or killed process ends it.
            socket.setSoLinger(true, 0)
        }
    }

    private fun call(name: String, arguments: String): JsonObject =
        rpc("tools/call", """{"name":"$name","arguments":$arguments}""").result()

    private fun text(result: JsonObject): String =
        (result["content"] as JsonArray).joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }

    private fun rpc(method: String, params: String): Response =
        post("""{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}""")

    private fun post(
        body: String,
        token: String? = TOKEN,
        path: String = "/mcp",
        headers: Map<String, String> = emptyMap(),
    ): Response {
        val all = headers + mapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json, text/event-stream",
        ) + (token?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap())
        return send("POST", path, body, all)
    }

    private fun send(
        method: String,
        path: String,
        body: String? = null,
        headers: Map<String, String> = mapOf("Authorization" to "Bearer $TOKEN"),
    ): Response {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 5_000
            connection.readTimeout = 10_000
            headers.forEach(connection::setRequestProperty)
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val text = stream?.use { String(it.readBytes()) }.orEmpty()
            return Response(status, connection.headerFields.filterKeys { it != null }, text)
        } finally {
            connection.disconnect()
        }
    }

    private fun assertJson(expected: String, actual: JsonElement) =
        assertEquals(Json.parseToJsonElement(expected), actual)

    private companion object {
        const val TOKEN = "s3cret-token"
        const val PING = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""

        val SCRIPT = """
            server.tool("weather", { title: "Weather", description: "Forecast", inputSchema: { city: "string", days: "integer?" } },
                (args) => args.city + ": " + (args.days ?? 1) + " days");
            server.tool("sum", "Adds numbers", (args) => ({
                total: args.numbers.reduce((a, b) => a + b, 0),
                count: args.numbers.length,
                first: args.numbers[0],
                label: args.meta.label,
            }));
            server.tool("later", "Async", async () => {
                await new Promise(resolve => setTimeout(resolve, 30));
                return "done";
            });
            server.tool("boom", "Throws", () => { throw new Error("kaboom"); });
            server.tool("never", "Never resolves", () => new Promise(() => {}));
            server.tool("slow", "Takes long", () => new Promise(resolve => setTimeout(() => resolve("late"), 5000)));
            server.tool("raw", "Raw result", () => ({
                content: [{ type: "text", text: "pic" }, { type: "image", data: "AAAA", mimeType: "image/png" }],
                structuredContent: { n: 1 },
            }));
            server.tool("info", "Server info", () => ({ running: server.running, port: server.port, tools: server.tools.length }));
        """.trimIndent()
    }
}
