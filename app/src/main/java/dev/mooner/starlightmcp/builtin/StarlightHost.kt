package dev.mooner.starlightmcp.builtin

import java.io.File

/** What the built-in tools reach of StarLight; tests replace it. */
interface StarlightHost {

    fun projects(): List<ProjectHandle>

    fun languages(): List<LanguageInfo>

    /** Recent logs of StarLight and its plugins, oldest first. */
    fun appLogs(): List<LogEntry>
}

interface ProjectHandle {

    val id: String

    val name: String

    val languageId: String

    /** Path of the main script, relative to [directory]. */
    val mainScript: String

    val isEnabled: Boolean

    val isCompiled: Boolean

    val activeJobs: Int

    val directory: File

    /** Compiles the project; throws with the reason if it fails. */
    fun compile()

    fun setEnabled(enabled: Boolean): Boolean

    /** The project's logs, oldest first. */
    fun logs(): List<LogEntry>

    /**
     * Sends [message] to the project's message event (`onMessage`) from a debug room, as StarLight's debug room does.
     * What the script sends to the room arrives at [onReply], and a failure of the event call at [onError]; both may
     * come later and on other threads. Throws if the project can't receive it now.
     */
    fun sendDebugMessage(message: DebugMessage, onReply: (String) -> Unit, onError: (Throwable) -> Unit)
}

/** A debug room message; null fields take the debug room settings of StarLight. */
class DebugMessage(
    val text: String,
    val sender: String? = null,
    val room: String? = null,
    val isGroupChat: Boolean? = null,
)

class LanguageInfo(
    val id: String,
    val name: String,
    val fileExtension: String,
)

class LogEntry(
    val type: String,
    val tag: String,
    val message: String,
    val millis: Long,
)
