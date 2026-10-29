package dev.mooner.starlightmcp.tool

import dev.mooner.starlightmcp.FakeOwner
import dev.mooner.starlightmcp.server.ServerInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mozilla.javascript.Context

/** Tools registered from Rhino scripts, which hand over JS functions and objects as Rhino values. */
class RhinoToolTest {

    private val registry = ToolRegistry()
    private val toolsets = ProjectToolsets(registry) { ServerInfo(false, 8765, emptyList()) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun registersAndCallsRhinoFunctions() {
        val server = toolsets.claim(FakeOwner())
        val cx = Context.enter()
        try {
            val global = cx.initStandardObjects()
            global.put("server", global, Context.javaToJS(server, global))
            cx.evaluateString(global, """
                server.tool("add", { description: "Adds", inputSchema: { a: "number", b: "number" } },
                    function (args) { return args.get("a") + args.get("b"); });
                server.tool("obj", "Object", function (args) { return { list: [1, "x"], n: args.get("n") }; });
            """.trimIndent(), "test.js", 1, null)
        } finally {
            Context.exit()
        }

        assertEquals(listOf("add", "obj"), server.getTools())
        assertEquals("Adds", registry.find("add")!!.spec.description)
        assertEquals("5", text("add", """{"a":2,"b":3}"""))
        assertEquals(Json.parseToJsonElement("""{"list":[1,"x"],"n":null}"""), Json.parseToJsonElement(text("obj", "{}")))
    }

    private fun text(name: String, arguments: String): String {
        val runner = ScriptToolRunner(scope) { 2_000 }
        val result = runBlocking { runner.run(registry.find(name)!!, Json.parseToJsonElement(arguments).jsonObject) }
        return result.toJson()["content"]!!.let { (it as kotlinx.serialization.json.JsonArray)[0].jsonObject["text"]!!.jsonPrimitive.content }
    }
}
