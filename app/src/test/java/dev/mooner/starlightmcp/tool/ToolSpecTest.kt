package dev.mooner.starlightmcp.tool

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Options as Rhino hands them over: JS objects are Maps. */
class ToolSpecTest {

    @Test
    fun descriptionOnly() {
        val spec = McpServer.parseSpec("t", "Does things")
        assertEquals("Does things", spec.description)
        assertJson("""{"type":"object","properties":{}}""", spec.inputSchema.toString())
    }

    @Test
    fun shorthandSchema() {
        val spec = McpServer.parseSpec("t", mapOf(
            "description" to "d",
            "title" to "T",
            "inputSchema" to mapOf("city" to "string", "days" to "integer?", "unit" to mapOf("enum" to listOf("c", "f"))),
        ))
        assertEquals("T", spec.title)
        assertJson(
            """{"type":"object","properties":{"city":{"type":"string"},"days":{"type":"integer"},"unit":{"enum":["c","f"]}},"required":["city","unit"]}""",
            spec.inputSchema.toString(),
        )
    }

    @Test
    fun fullSchemaIsKept() {
        val schema = mapOf("type" to "object", "properties" to mapOf("q" to mapOf("type" to "string")), "required" to listOf("q"))
        val spec = McpServer.parseSpec("t", mapOf("inputSchema" to schema))
        assertJson("""{"type":"object","properties":{"q":{"type":"string"}},"required":["q"]}""", spec.inputSchema.toString())
    }

    @Test
    fun unknownTypeFails() {
        assertThrows(IllegalArgumentException::class.java) {
            McpServer.parseSpec("t", mapOf("inputSchema" to mapOf("x" to "str")))
        }
    }

    @Test
    fun resultsOfScripts() {
        assertJson("""{"content":[{"type":"text","text":"hi"}]}""", ScriptToolRunner.toResult("hi").toJson().toString())
        assertJson("""{"content":[{"type":"text","text":"3"}]}""", ScriptToolRunner.toResult(3.0).toJson().toString())
        assertJson("""{"content":[{"type":"text","text":"{\"a\":[1,2]}"}]}""", ScriptToolRunner.toResult(mapOf("a" to listOf(1.0, 2.0))).toJson().toString())
        assertJson("""{"content":[]}""", ScriptToolRunner.toResult(null).toJson().toString())
        val raw = mapOf("content" to listOf(mapOf("type" to "text", "text" to "x")), "isError" to true)
        assertJson("""{"content":[{"type":"text","text":"x"}],"isError":true}""", ScriptToolRunner.toResult(raw).toJson().toString())
    }

    private fun assertJson(expected: String, actual: String) =
        assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(actual))
}
