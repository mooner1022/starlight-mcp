package dev.mooner.starlightmcp.tool

import dev.mooner.starlightmcp.server.ServerInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Hands each compilation of a project its own [McpServer].
 *
 * A compilation reaches it in up to two ways: the `Mcp` API object, created while the script is being compiled, and
 * the setup event, fired once compiling ends. [open] starts a generation for the former, [claim] takes it over for
 * the latter (or starts one if the API was not used). Starting a generation drops the tools of the last one, and
 * servers of earlier generations stop accepting tools, so handlers of a dead script scope never stay.
 */
class ProjectToolsets(
    private val registry: ToolRegistry,
    private val serverInfo: () -> ServerInfo,
) {

    private class Slot(
        val server: McpServer,
        var isClaimed: Boolean,
        var isSetUp: Boolean = false,
    )

    private val slots: MutableMap<String, Slot> = ConcurrentHashMap()
    private val generations = AtomicLong()

    /** Starts a generation for a compilation that is starting. */
    @Synchronized
    fun open(owner: ToolOwner): McpServer =
        newSlot(owner, isClaimed = false).server

    /** The server of a compilation that ended: the one [open] started for it, or a new one. */
    @Synchronized
    fun claim(owner: ToolOwner): McpServer {
        val slot = slots[owner.id]
            ?.takeUnless(Slot::isClaimed)
            ?: return newSlot(owner, isClaimed = true).server
        slot.isClaimed = true
        return slot.server
    }

    fun current(ownerId: String): McpServer? =
        slots[ownerId]?.server

    /**
     * Marks the server of [generation] as set up; false if it already was, is no longer current, or its compilation
     * hasn't ended (calling setup then would run the previous script against the new server).
     */
    @Synchronized
    fun markSetUp(ownerId: String, generation: Long): Boolean {
        val slot = slots[ownerId]
            ?.takeIf { it.server.generation == generation && it.isClaimed && !it.isSetUp }
            ?: return false
        slot.isSetUp = true
        return true
    }

    fun isCurrent(ownerId: String, generation: Long): Boolean =
        slots[ownerId]?.server?.generation == generation

    @Synchronized
    fun close(ownerId: String) {
        slots -= ownerId
        registry.removeOwner(ownerId)
    }

    private fun newSlot(owner: ToolOwner, isClaimed: Boolean): Slot {
        registry.removeOwner(owner.id)
        val server = McpServer(owner, generations.incrementAndGet(), registry, this, serverInfo)
        return Slot(server, isClaimed).also { slots[owner.id] = it }
    }
}
