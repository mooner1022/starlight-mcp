package dev.mooner.starlightmcp.builtin

import dev.mooner.starlight.plugincore.Session
import dev.mooner.starlight.plugincore.logger.LogData
import dev.mooner.starlight.plugincore.project.Project
import java.io.File

/** StarLight as the running app has it. */
class SessionHost(
    private val appLogs: LogBuffer,
) : StarlightHost {

    override fun projects(): List<ProjectHandle> =
        Session.projectManager.getProjects().map(::ProjectAdapter)

    override fun languages(): List<LanguageInfo> =
        Session.languageManager.getLanguages().map { LanguageInfo(it.id, it.name, it.fileExtension) }

    override fun appLogs(): List<LogEntry> =
        appLogs.snapshot()

    private class ProjectAdapter(
        private val project: Project,
    ) : ProjectHandle {

        override val id: String
            get() = project.info.id.toString()

        override val name: String
            get() = project.info.name

        override val languageId: String
            get() = project.info.languageId

        override val mainScript: String
            get() = project.info.mainScript

        override val isEnabled: Boolean
            get() = project.info.isEnabled

        override val isCompiled: Boolean
            get() = project.isCompiled

        override val activeJobs: Int
            get() = project.activeJobs()

        override val directory: File
            get() = project.directory

        override fun compile() {
            check(project.compile(true)) { "Compilation failed" }
        }

        override fun setEnabled(enabled: Boolean): Boolean =
            project.setEnabled(enabled)

        override fun logs(): List<LogEntry> =
            project.logger.logs.map(LogData::toEntry)
    }
}

internal fun LogData.toEntry(): LogEntry =
    LogEntry(type.name, tag.orEmpty(), message, millis)
