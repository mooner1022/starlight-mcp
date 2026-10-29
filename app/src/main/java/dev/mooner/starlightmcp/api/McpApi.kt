package dev.mooner.starlightmcp.api

import dev.mooner.starlight.plugincore.api.Api
import dev.mooner.starlight.plugincore.api.ApiObject
import dev.mooner.starlight.plugincore.api.InstanceType
import dev.mooner.starlight.plugincore.project.Project
import dev.mooner.starlightmcp.McpRuntime
import dev.mooner.starlightmcp.project.ProjectToolOwner
import dev.mooner.starlightmcp.tool.McpServer

/**
 * The project's MCP server as the global `Mcp`, for registering tools from top-level code.
 *
 * StarLight creates the instance while compiling the project, which starts the server's generation; the setup event
 * fired after the compilation gets the same server. NeonJS doesn't expose plugin APIs yet, so for now only Rhino
 * scripts see this global; the setup event works in both.
 */
class McpApi : Api<McpServer>() {

    override val name: String = "Mcp"

    override val objects: List<ApiObject> =
        getApiObjects<McpServer>()

    override val instanceClass: Class<McpServer> =
        McpServer::class.java

    override val instanceType: InstanceType =
        InstanceType.OBJECT

    override fun getInstance(project: Project): Any =
        McpRuntime.toolsets.open(ProjectToolOwner(project))
}
