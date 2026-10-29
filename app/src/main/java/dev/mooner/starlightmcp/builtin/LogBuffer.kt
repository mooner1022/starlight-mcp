package dev.mooner.starlightmcp.builtin

import java.util.ArrayDeque

/** The latest [capacity] logs. StarLight keeps no list of app logs, so they are collected from its log events. */
class LogBuffer(
    private val capacity: Int = 500,
) {

    private val logs = ArrayDeque<LogEntry>(capacity)

    @Synchronized
    fun add(entry: LogEntry) {
        if (logs.size == capacity)
            logs.removeFirst()
        logs.addLast(entry)
    }

    /** Oldest first. */
    @Synchronized
    fun snapshot(): List<LogEntry> =
        logs.toList()
}
