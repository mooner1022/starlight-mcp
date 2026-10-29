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
}

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
