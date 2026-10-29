package dev.mooner.starlightmcp.event

import dev.mooner.starlight.plugincore.language.CodeGenerator
import dev.mooner.starlight.plugincore.project.event.ProjectEvent
import dev.mooner.starlight.plugincore.translation.Locale
import dev.mooner.starlight.plugincore.translation.translate
import dev.mooner.starlightmcp.tool.McpServer

/** Called after each compilation of a project, with the project's MCP server. */
class McpSetupEvent : ProjectEvent() {

    override val functionName: String = FUNCTION_NAME

    override val id: String = "setup"

    override val name: String = translate {
        Locale.ENGLISH { "MCP tool setup" }
        Locale.KOREAN  { "MCP 도구 설정" }
    }

    override val functionComment: String = translate {
        Locale.ENGLISH {
            """
                |Called after the project is compiled. Register tools that MCP clients (AI assistants) can call:
                |server.tool("greet", { description: "Greets someone", inputSchema: { name: "string" } }, (args) => "Hello, " + args.name);
                |Tools of the last compilation are removed before this is called.
                |In NeonJS, the function must be exported.
            """.trimMargin()
        }
        Locale.KOREAN {
            """
                |프로젝트가 컴파일된 후 호출됩니다. MCP 클라이언트(AI 어시스턴트)가 호출할 도구를 등록하세요:
                |server.tool("greet", { description: "인사합니다", inputSchema: { name: "string" } }, (args) => "안녕, " + args.name);
                |이 함수가 호출되기 전 이전 컴파일에서 등록한 도구는 모두 제거됩니다.
                |NeonJS에서는 함수를 export 해야 합니다.
            """.trimMargin()
        }
    }

    override val argTypes: Array<CodeGenerator.Argument<*>> =
        arrayOf("server" typedAs McpServer::class)

    companion object {
        const val FUNCTION_NAME = "onMcpSetup"
    }
}
