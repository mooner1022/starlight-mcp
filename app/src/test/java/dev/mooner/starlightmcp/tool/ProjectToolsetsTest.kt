package dev.mooner.starlightmcp.tool

import dev.mooner.starlightmcp.FakeOwner
import dev.mooner.starlightmcp.server.ServerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectToolsetsTest {

    private val owner = FakeOwner()
    private val registry = ToolRegistry()
    private val toolsets = ProjectToolsets(registry) { ServerInfo(false, 8765, emptyList()) }

    @Test
    fun setupEventGetsTheServerTheApiOpened() {
        val fromApi = toolsets.open(owner)
        fromApi.tool("top_level", "d", ToolHandler { "" })
        val fromEvent = toolsets.claim(owner)
        assertSame(fromApi, fromEvent)
        assertEquals(listOf("top_level"), fromEvent.getTools())
    }

    @Test
    fun eachCompilationStartsOver() {
        val first = toolsets.claim(owner)
        first.tool("old", "d", ToolHandler { "" })
        val second = toolsets.claim(owner)
        assertNotSame(first, second)
        assertEquals(emptyList<String>(), second.getTools())

        // A server kept by the old script scope can't add tools anymore.
        first.tool("stale", "d", ToolHandler { "" })
        assertEquals(0, registry.size)
        assertTrue(owner.errors.single().contains("recompiled"))
    }

    @Test
    fun setUpOncePerGeneration() {
        val server = toolsets.claim(owner)
        assertTrue(toolsets.markSetUp(owner.id, server.generation))
        assertFalse(toolsets.markSetUp(owner.id, server.generation))
        assertFalse(toolsets.markSetUp(owner.id, server.generation - 1))
    }

    @Test
    fun noSetupWhileCompiling() {
        val server = toolsets.open(owner)
        assertFalse(toolsets.markSetUp(owner.id, server.generation))
        toolsets.claim(owner)
        assertTrue(toolsets.markSetUp(owner.id, server.generation))
    }

    @Test
    fun closeDropsTools() {
        toolsets.claim(owner).tool("x", "d", ToolHandler { "" })
        toolsets.close(owner.id)
        assertEquals(0, registry.size)
        assertEquals(null, toolsets.current(owner.id))
    }

    @Test
    fun firstProjectKeepsAName() {
        val other = FakeOwner("other")
        toolsets.claim(owner).tool("same", "mine", ToolHandler { "" })
        toolsets.claim(other).tool("same", "theirs", ToolHandler { "" })
        assertEquals("mine", registry.find("same")!!.spec.description)
        assertTrue(other.errors.single().contains("already taken"))

        toolsets.close(owner.id)
        assertEquals("theirs", registry.find("same")!!.spec.description)
    }

    @Test
    fun rejectsReservedAndInvalidNames() {
        val server = toolsets.claim(owner)
        assertThrows(IllegalArgumentException::class.java) { server.tool("starlight_x", "d", ToolHandler { "" }) }
        assertThrows(IllegalArgumentException::class.java) { server.tool("has space", "d", ToolHandler { "" }) }
        assertThrows(IllegalArgumentException::class.java) { server.tool("dotted.name", "d", ToolHandler { "" }) }
    }
}
