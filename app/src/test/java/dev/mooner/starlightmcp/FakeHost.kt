package dev.mooner.starlightmcp

import dev.mooner.starlightmcp.builtin.LanguageInfo
import dev.mooner.starlightmcp.builtin.LogEntry
import dev.mooner.starlightmcp.builtin.ProjectHandle
import dev.mooner.starlightmcp.builtin.StarlightHost
import java.io.File

class FakeHost(
    val projects: MutableList<FakeProject> = arrayListOf(),
) : StarlightHost {

    val appLogs: MutableList<LogEntry> = arrayListOf()

    override fun projects(): List<ProjectHandle> = projects

    override fun languages(): List<LanguageInfo> =
        listOf(LanguageInfo("JS_NEON", "NeonJS", "js"))

    override fun appLogs(): List<LogEntry> = appLogs
}

class FakeProject(
    override val name: String,
    override val directory: File,
    override val id: String = "id-$name",
    override val mainScript: String = "index.js",
) : ProjectHandle {

    override val languageId: String = "JS_NEON"

    override var isEnabled: Boolean = true

    override var isCompiled: Boolean = false

    override val activeJobs: Int = 0

    /** Thrown by [compile] when set. */
    var compileError: String? = null

    val logs: MutableList<LogEntry> = arrayListOf()

    override fun compile() {
        logs += LogEntry("INFO", name, "compiling", System.currentTimeMillis())
        compileError?.let { throw IllegalStateException(it) }
        isCompiled = true
    }

    override fun setEnabled(enabled: Boolean): Boolean {
        isEnabled = enabled
        return true
    }

    override fun logs(): List<LogEntry> = logs
}
