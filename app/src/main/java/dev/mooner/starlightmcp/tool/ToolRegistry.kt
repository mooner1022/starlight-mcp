package dev.mooner.starlightmcp.tool

import java.util.concurrent.CopyOnWriteArrayList

/** Tools registered by projects. Lookups don't lock; changes are serialized. */
class ToolRegistry {

    class Entry internal constructor(
        val owner: ToolOwner,
        val generation: Long,
        val spec: ToolSpec,
        val function: ScriptFunction,
    ) {
        override fun toString(): String =
            "${spec.name} [${owner.name}]"
    }

    private val entries: MutableList<Entry> = CopyOnWriteArrayList()

    val size: Int
        get() = entries.size

    /**
     * Adds a tool, replacing the owner's tool of the same name. Returns the entry of another owner that already has
     * the name; the new tool stays hidden behind it until that one is removed.
     */
    @Synchronized
    fun add(owner: ToolOwner, generation: Long, spec: ToolSpec, function: ScriptFunction): Entry? {
        entries.removeAll { it.owner.id == owner.id && it.spec.name == spec.name }
        val shadowedBy = entries.firstOrNull { it.owner.id != owner.id && it.spec.name == spec.name }
        entries += Entry(owner, generation, spec, function)
        return shadowedBy
    }

    @Synchronized
    fun remove(ownerId: String, name: String): Boolean =
        entries.removeAll { it.owner.id == ownerId && it.spec.name == name }

    @Synchronized
    fun removeOwner(ownerId: String): Int {
        val owned = entries.filter { it.owner.id == ownerId }
        entries.removeAll(owned)
        return owned.size
    }

    fun toolsOf(ownerId: String): List<Entry> =
        entries.filter { it.owner.id == ownerId }

    /** Tools clients can call: the earliest registered one of each name. */
    fun visible(): List<Entry> =
        entries.distinctBy { it.spec.name }

    fun find(name: String): Entry? =
        entries.firstOrNull { it.spec.name == name }
}
