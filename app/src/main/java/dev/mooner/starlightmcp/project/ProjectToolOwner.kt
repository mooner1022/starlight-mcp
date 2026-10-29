package dev.mooner.starlightmcp.project

import dev.mooner.starlight.plugincore.project.Project
import dev.mooner.starlightmcp.tool.ToolOwner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.ContinuationInterceptor

class ProjectToolOwner(
    private val project: Project,
) : ToolOwner {

    override val id: String =
        project.info.id.toString()

    override val name: String
        get() = project.info.name

    override val isActive: Boolean
        get() = project.isCompiled && project.info.isEnabled

    // Handlers run on the project's own threads, as its events do.
    override val dispatcher: CoroutineDispatcher
        get() = project.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher ?: Dispatchers.IO

    override fun logInfo(message: String) =
        project.logger.info(message)

    override fun logError(message: String) =
        project.logger.error(message)
}
