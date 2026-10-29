package dev.mooner.starlightmcp

import android.text.InputType
import dev.mooner.configdsl.ConfigStructure
import dev.mooner.configdsl.Icon
import dev.mooner.configdsl.config
import dev.mooner.configdsl.options.button
import dev.mooner.configdsl.options.password
import dev.mooner.configdsl.options.seekbar
import dev.mooner.configdsl.options.spinner
import dev.mooner.configdsl.options.string
import dev.mooner.configdsl.options.toggle
import dev.mooner.starlight.plugincore.Session
import dev.mooner.starlight.plugincore.config.data.ConfigData
import dev.mooner.starlight.plugincore.event.EventHandler
import dev.mooner.starlight.plugincore.event.Events
import dev.mooner.starlight.plugincore.event.on
import dev.mooner.starlight.plugincore.logger.LogData
import dev.mooner.starlight.plugincore.logger.LoggerFactory
import dev.mooner.starlight.plugincore.plugin.StarlightPlugin
import dev.mooner.starlight.plugincore.project.Project
import dev.mooner.starlight.plugincore.project.event.ProjectEventManager
import dev.mooner.starlightmcp.api.McpApi
import dev.mooner.starlightmcp.builtin.BuiltinAccess
import dev.mooner.starlightmcp.builtin.BuiltinTools
import dev.mooner.starlightmcp.event.McpSetupEvent
import dev.mooner.starlightmcp.project.ProjectToolOwner
import dev.mooner.starlightmcp.protocol.McpProtocol
import dev.mooner.starlightmcp.server.HttpServerEngine
import dev.mooner.starlightmcp.server.McpHttpHandler
import dev.mooner.starlightmcp.server.ServerSettings
import dev.mooner.starlightmcp.server.ServerState
import dev.mooner.starlightmcp.tool.McpServer
import dev.mooner.starlightmcp.tool.ScriptToolRunner
import dev.mooner.starlightmcp.utils.ANY_HOST
import dev.mooner.starlightmcp.utils.LOCALHOST
import dev.mooner.starlightmcp.utils.copyServerUrl
import dev.mooner.starlightmcp.utils.sha256Hex
import dev.mooner.starlightmcp.utils.tr
import dev.mooner.starlightworkspace.Workspaces
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private val LOG = LoggerFactory.logger("McpPlugin")

@Suppress("unused")
class StarlightMcpPlugin : StarlightPlugin() {

    // The server is started and stopped on this thread only: binding is network I/O, which Android forbids on the
    // main thread (config buttons are clicked there).
    private val serverExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "StarlightMcp-Server").apply {
            isDaemon = true
            contextClassLoader = StarlightMcpPlugin::class.java.classLoader
        }
    }

    /** Lives from onEnable to onDisable; runs event subscriptions and tool handlers. */
    private var pluginScope: CoroutineScope? = null

    /** Accessed on [serverExecutor] only. */
    private var engine: HttpServerEngine? = null

    private val builtinTools = BuiltinTools { Workspaces.current }

    @Volatile
    private var settings = ServerSettings()
    @Volatile
    private var builtinAccess = BuiltinAccess.READ_WRITE
    @Volatile
    private var toolTimeoutMillis = DEF_TIMEOUT_SECONDS * 1000L
    @Volatile
    private var host: String = ANY_HOST
    @Volatile
    private var port: Int = McpRuntime.DEFAULT_PORT
    private var autoStart = true

    private val isRunning: Boolean
        get() = McpRuntime.state.value is ServerState.Running

    override fun onEnable() {
        // StarLight checks only the major and minor version of the workspace plugin, not the patch this was built for.
        if (Workspaces.apiLevel < Workspaces.API_LEVEL) {
            LOG.error(LogData.FLAG_NOTIFY) {
                tr(
                    "Update the Workspace plugin: MCP Server needs its API level ${Workspaces.API_LEVEL} or later.",
                    "Workspace 플러그인을 업데이트하세요: MCP 서버에는 API 레벨 ${Workspaces.API_LEVEL} 이상이 필요해요.",
                )
            }
            return
        }
        updateConfig(getPluginConfig())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        pluginScope = scope

        registerProjectEvents {
            category("tools") {
                add<McpSetupEvent>()
            }
        }
        addApi(McpApi())

        EventHandler.on<Events.Project.Compile>(scope) { setUpProject(project) }
        EventHandler.on<Events.Project.InfoUpdate>(scope) { onProjectInfoUpdated(project) }
        EventHandler.on<Events.Project.Delete>(scope) { McpRuntime.toolsets.close(projectId.toString()) }
        // Projects compiled before this plugin was enabled.
        for (project in Session.projectManager.getProjects())
            if (project.isCompiled && McpRuntime.toolsets.current(project.info.id.toString()) == null)
                setUpProject(project)

        if (autoStart)
            serverExecutor.execute(::startServer)
    }

    override fun onDisable() {
        pluginScope?.cancel()
        pluginScope = null
        serverExecutor.execute(::stopServer)
    }

    override fun onConfigUpdated(config: ConfigData, updated: Map<String, Set<String>>) {
        super.onConfigUpdated(config, updated)
        val oldHost = host
        val oldPort = port
        updateConfig(config)
        if ((host != oldHost || port != oldPort) && isRunning) {
            LOG.info { tr("Restarting the server on $host:$port", "$host:$port 에서 서버를 다시 시작해요.") }
            serverExecutor.execute(::restartServer)
        }
    }

    private fun updateConfig(config: ConfigData) {
        with(config.category(CATEGORY_GENERAL)) {
            port = getString(CONF_PORT)?.toIntOrNull()?.takeIf(::isValidPort) ?: McpRuntime.DEFAULT_PORT
            host = if (getInt(CONF_BIND, BIND_ALL) == BIND_LOCAL) LOCALHOST else ANY_HOST
            autoStart = getBoolean(CONF_AUTO_START, true)
        }
        McpRuntime.configuredPort = port

        val security = config.category(CATEGORY_SECURITY)
        settings = ServerSettings(
            tokenHash = security.getString(CONF_TOKEN)?.takeUnless { it.isBlank() || it == EMPTY_TOKEN_HASH },
            requireToken = security.getBoolean(CONF_REQUIRE_TOKEN, true),
            logCalls = config.category(CATEGORY_LOGGING).getBoolean(CONF_LOG_CALLS, false),
        )
        builtinAccess = BuiltinAccess.entries.getOrElse(security.getInt(CONF_BUILTIN_ACCESS, BuiltinAccess.READ_WRITE.ordinal)) { BuiltinAccess.READ_WRITE }
        toolTimeoutMillis = config.category(CATEGORY_TOOLS).getInt(CONF_TIMEOUT, DEF_TIMEOUT_SECONDS) * 1000L
    }

    // ------------------------------------------------------------------ projects

    /** Starts a tool generation for a compiled [project] and calls its setup function with it. */
    private fun setUpProject(project: Project) {
        val server = McpRuntime.toolsets.claim(ProjectToolOwner(project))
        callSetup(project, server)
    }

    /** Runs the setup of a project whose setup event was enabled after it was compiled. */
    private fun onProjectInfoUpdated(project: Project) {
        if (!project.isCompiled)
            return
        val server = McpRuntime.toolsets.current(project.info.id.toString()) ?: return
        callSetup(project, server)
    }

    private fun callSetup(project: Project, server: McpServer) {
        val eventId = ProjectEventManager.validateAndGetEventID(McpSetupEvent::class) ?: return
        if (!project.isCompiled || !project.isEventCallAllowed(eventId))
            return
        if (!McpRuntime.toolsets.markSetUp(project.info.id.toString(), server.generation))
            return
        project.callFunction(McpSetupEvent.FUNCTION_NAME, arrayOf(server)) { e ->
            LOG.error {
                tr(
                    "Error while setting up MCP tools of project ${project.info.name}: ${e.message}",
                    "프로젝트 ${project.info.name}의 MCP 도구 설정 중 오류 발생: ${e.message}",
                )
            }
        }
    }

    // ------------------------------------------------------------------ server

    /** Runs on [serverExecutor]. */
    private fun startServer() {
        if (engine != null)
            return
        val scope = pluginScope ?: return
        if (settings.requireToken && settings.tokenHash == null)
            LOG.warn {
                tr(
                    "MCP access token is not set: every request will be rejected. Set one in the plugin settings.",
                    "MCP 접근 토큰이 설정되지 않아 모든 요청이 거부돼요. 플러그인 설정에서 토큰을 지정하세요.",
                )
            }
        val host = host
        val port = port
        McpRuntime.setState(ServerState.Starting)
        val protocol = McpProtocol(
            serverName = SERVER_NAME,
            serverVersion = info.version.toString(),
            instructions = INSTRUCTIONS,
            builtins = { builtinTools.tools(builtinAccess) },
            registry = McpRuntime.registry,
            runner = ScriptToolRunner(scope) { toolTimeoutMillis },
            onToolCall = { name, project, result, durationMillis ->
                if (settings.logCalls)
                    LOG.info {
                        val status = if (result.isError) "error" else "ok"
                        "tools/call $name -> $status (${durationMillis}ms)" + (project?.let { " [$it]" } ?: "")
                    }
            },
        )
        val engine = HttpServerEngine(McpHttpHandler(protocol, { settings }) { message -> LOG.info { message } })
        try {
            engine.start(host, port)
        } catch (e: Throwable) {
            McpRuntime.setState(ServerState.Failed(e.message ?: e.toString()))
            LOG.error {
                tr("Failed to start the MCP server on $host:$port: $e", "$host:$port 에서 MCP 서버를 시작하지 못했어요: $e")
            }
            return
        }
        this.engine = engine
        McpRuntime.setState(ServerState.Running(host, port, System.currentTimeMillis()))
        val urls = McpRuntime.serverInfo().urls.joinToString()
        LOG.info { tr("MCP server started: $urls", "MCP 서버 시작됨: $urls") }
    }

    /** Runs on [serverExecutor]. */
    private fun stopServer() {
        val engine = engine ?: return
        this.engine = null
        try {
            engine.stop()
        } catch (e: Exception) {
            LOG.error { tr("Error while stopping the MCP server: $e", "MCP 서버 종료 중 오류 발생: $e") }
        }
        McpRuntime.setState(ServerState.Stopped)
        LOG.info { tr("MCP server stopped", "MCP 서버가 종료되었어요.") }
    }

    private fun restartServer() {
        stopServer()
        startServer()
    }

    // ------------------------------------------------------------------ config

    override fun getConfigStructure(): ConfigStructure = config {
        category {
            id = CATEGORY_GENERAL
            title = tr("General", "일반")
            textColor = color { COLOR_TITLE }
            items {
                string {
                    id = CONF_PORT
                    title = tr("Port", "포트")
                    hint = McpRuntime.DEFAULT_PORT.toString()
                    defaultValue = McpRuntime.DEFAULT_PORT.toString()
                    inputType = InputType.TYPE_CLASS_NUMBER
                    require = { value ->
                        if (value.toIntOrNull()?.let(::isValidPort) == true) null
                        else tr("Enter a port between $MIN_PORT and $MAX_PORT", "$MIN_PORT~$MAX_PORT 사이의 포트를 입력하세요")
                    }
                    icon = Icon.LINK
                    iconTintColor = color { COLOR_ICON }
                }
                spinner {
                    id = CONF_BIND
                    title = tr("Accept connections from", "접속 허용 범위")
                    items = listOf(
                        tr("All networks ($ANY_HOST)", "모든 네트워크 ($ANY_HOST)"),
                        tr("This device only ($LOCALHOST)", "이 기기에서만 ($LOCALHOST)"),
                    )
                    defaultIndex = BIND_ALL
                    icon = Icon.CLOUD
                    iconTintColor = color { COLOR_ICON }
                }
                toggle {
                    id = CONF_AUTO_START
                    title = tr("Start automatically", "자동 시작")
                    description = tr("Starts the server when StarLight starts.", "StarLight 시작 시 서버를 함께 시작합니다.")
                    defaultValue = true
                    icon = Icon.POWER
                    iconTintColor = color { COLOR_ICON }
                }
            }
        }
        category {
            id = CATEGORY_CONTROL
            title = tr("Control", "제어")
            textColor = color { COLOR_SUB_TITLE }
            items {
                button {
                    val state = McpRuntime.state.value
                    id = "state_indicator"
                    title = when (state) {
                        is ServerState.Running -> tr("Running on ${McpRuntime.serverInfo().urls.joinToString()}", "작동 중: ${McpRuntime.serverInfo().urls.joinToString()}")
                        ServerState.Starting   -> tr("Starting...", "시작 중...")
                        ServerState.Stopped    -> tr("Stopped", "중지됨")
                        is ServerState.Failed  -> tr("Failed: ${state.reason}", "오류: ${state.reason}")
                    }
                    description = if (state is ServerState.Running) tr("Tap to copy the address.", "눌러서 주소를 복사합니다.") else null
                    icon = Icon.POWER
                    iconTintColor = color {
                        when (state) {
                            is ServerState.Running -> "#4ADE80"
                            ServerState.Starting   -> "#F2B05E"
                            else                   -> "#FF6188"
                        }
                    }
                    setOnClickListener { view -> copyServerUrl(view.context) }
                }
                button {
                    val action = if (isRunning) tr("Stop", "중지") else tr("Start", "시작")
                    id = "toggle_server"
                    title = tr("$action server", "서버 $action")
                    icon = Icon.POWER
                    iconTintColor = color { "#FF6188" }
                    setOnClickListener { _ ->
                        LOG.info(LogData.FLAG_NOTIFY) { tr("$action server...", "서버 $action 중...") }
                        serverExecutor.execute {
                            if (engine != null) stopServer() else startServer()
                        }
                    }
                }
                button {
                    id = "restart_server"
                    title = tr("Restart server", "서버 재시작")
                    description = tr("Tools registered by projects are kept.", "프로젝트가 등록한 도구는 유지됩니다.")
                    icon = Icon.REFRESH
                    iconTintColor = color { "#FF6188" }
                    setOnClickListener { _ ->
                        LOG.info(LogData.FLAG_NOTIFY) { tr("Restarting server...", "서버 재시작 중...") }
                        serverExecutor.execute(::restartServer)
                    }
                }
            }
        }
        category {
            id = CATEGORY_SECURITY
            title = tr("Security", "보안")
            textColor = color { COLOR_SUB_TITLE }
            items {
                toggle {
                    id = CONF_REQUIRE_TOKEN
                    title = tr("Require access token", "접근 토큰 필수")
                    description = tr(
                        "Clients must send 'Authorization: Bearer <token>'. Without a token, anyone on the network can edit and run your scripts.",
                        "클라이언트가 'Authorization: Bearer <토큰>' 헤더를 보내야 합니다. 끄면 같은 네트워크의 누구나 스크립트를 수정하고 실행할 수 있습니다.",
                    )
                    defaultValue = true
                    icon = Icon.LOCK
                    iconTintColor = color { COLOR_ICON }
                }
                password {
                    id = CONF_TOKEN
                    title = tr("Access token", "접근 토큰")
                    description = tr("Only a hash of the token is stored.", "토큰은 해시로만 저장됩니다.")
                    hashCode = ::sha256Hex
                    require = { value -> if (value.length >= MIN_TOKEN_LENGTH) null else tr("At least $MIN_TOKEN_LENGTH characters", "${MIN_TOKEN_LENGTH}자 이상 입력하세요") }
                    icon = Icon.KEY
                    iconTintColor = color { COLOR_ICON }
                    dependency = CONF_REQUIRE_TOKEN
                }
                spinner {
                    id = CONF_BUILTIN_ACCESS
                    title = tr("Built-in StarLight tools", "StarLight 내장 도구")
                    items = listOf(
                        tr("Off", "끔"),
                        tr("Read only (projects, files, logs)", "읽기 전용 (프로젝트, 파일, 로그)"),
                        tr("Read & write (also edit files, compile, turn on/off, debug room)", "읽기·쓰기 (파일 수정, 컴파일, 켜기/끄기, 디버그룸 포함)"),
                    )
                    defaultIndex = BuiltinAccess.READ_WRITE.ordinal
                    icon = Icon.EDIT
                    iconTintColor = color { COLOR_ICON }
                }
            }
        }
        category {
            id = CATEGORY_TOOLS
            title = tr("Project tools", "프로젝트 도구")
            textColor = color { COLOR_SUB_TITLE }
            items {
                seekbar {
                    id = CONF_TIMEOUT
                    title = tr("Tool timeout (seconds)", "도구 제한 시간 (초)")
                    description = tr("Tools that don't return in time fail.", "시간 내에 결과를 돌려주지 않은 도구 호출은 실패합니다.")
                    min = 1
                    max = 300
                    defaultValue = DEF_TIMEOUT_SECONDS
                    icon = Icon.TIMER
                    iconTintColor = color { COLOR_ICON }
                }
            }
        }
        category {
            id = CATEGORY_LOGGING
            title = tr("Logging", "로그")
            textColor = color { COLOR_SUB_TITLE }
            items {
                toggle {
                    id = CONF_LOG_CALLS
                    title = tr("Log tool calls", "도구 호출 로그 표시")
                    description = tr("Logs every tool call with its result and duration.", "모든 도구 호출을 결과, 처리 시간과 함께 로그에 남깁니다.")
                    defaultValue = false
                    icon = Icon.LIST_BULLETED
                    iconTintColor = color { COLOR_ICON }
                }
            }
        }
    }

    private companion object {
        const val CATEGORY_GENERAL  = "general"
        const val CATEGORY_CONTROL  = "control"
        const val CATEGORY_SECURITY = "security"
        const val CATEGORY_TOOLS    = "tools"
        const val CATEGORY_LOGGING  = "logging"

        const val CONF_PORT           = "port"
        const val CONF_BIND           = "bind_address"
        const val CONF_AUTO_START     = "auto_start"
        const val CONF_REQUIRE_TOKEN  = "require_token"
        const val CONF_TOKEN          = "access_token"
        const val CONF_BUILTIN_ACCESS = "builtin_access"
        const val CONF_TIMEOUT        = "tool_timeout"
        const val CONF_LOG_CALLS      = "log_calls"

        const val BIND_ALL   = 0
        const val BIND_LOCAL = 1

        // Ports below 1024 need root on Android.
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
        const val MIN_TOKEN_LENGTH = 8
        const val DEF_TIMEOUT_SECONDS = 60

        val EMPTY_TOKEN_HASH = sha256Hex("")

        const val SERVER_NAME = "starlight"

        // Read by the client's model, so it is in English.
        val INSTRUCTIONS = """
            This server runs inside StarLight, an Android app that runs chatbot scripts called projects.
            Use starlight_list_projects to see the projects, and starlight_read_file / starlight_list_files to read their code.
            After changing a script with starlight_write_file, call starlight_compile_project to apply it and read the logs it returns.
            starlight_send_debug_message sends a chat message to a project from its debug room and returns the bot's replies, to try a bot out.
            starlight_get_project_logs shows what a running project logged. Other tools are defined by the projects themselves.
        """.trimIndent()

        const val COLOR_TITLE     = "#A78BFA"
        const val COLOR_SUB_TITLE = "#8B7BD8"
        const val COLOR_ICON      = "#A78BFA"

        fun isValidPort(port: Int): Boolean =
            port in MIN_PORT..MAX_PORT
    }
}
