package dev.mooner.starlightmcp.builtin

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.mooner.starlight.plugincore.Session
import dev.mooner.starlight.plugincore.chat.ChatSender
import dev.mooner.starlight.plugincore.chat.DebugChatRoom
import dev.mooner.starlight.plugincore.chat.Message
import dev.mooner.starlight.plugincore.config.GlobalConfig
import dev.mooner.starlight.plugincore.logger.LogData
import dev.mooner.starlight.plugincore.project.Project
import dev.mooner.starlight.plugincore.project.event.ProjectEvent
import dev.mooner.starlight.plugincore.project.event.ProjectEventManager
import java.io.File
import kotlin.reflect.KClass

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

        override fun sendDebugMessage(message: DebugMessage, onReply: (String) -> Unit, onError: (Throwable) -> Unit) {
            check(project.isCompiled) { "Project '$name' is not compiled" }
            val event = DebugRoom.messageEvent()
            val eventId = ProjectEventManager.validateAndGetEventID(event)
                ?: error("StarLight's message event is not registered")
            check(project.isEventCallAllowed(eventId)) {
                "Project '$name' doesn't receive messages: allow the message event (onMessage) in its settings"
            }
            val room = GlobalConfig.category("d_room")
            val data = Message(
                message = message.text,
                image = null,
                sender = ChatSender(
                    name = message.sender ?: GlobalConfig.category("d_user").getString("name", DebugRoom.DEFAULT_SENDER),
                    id = null,
                    profileBitmap = DebugRoom.profileBitmap(),
                ),
                room = DebugChatRoom(
                    id = room.getString("id") ?: DebugRoom.DEFAULT_ROOM_ID,
                    name = message.room ?: room.getString("name", DebugRoom.DEFAULT_ROOM),
                    isGroupChat = message.isGroupChat ?: room.getBoolean("is_group_chat", false),
                    onSend = onReply,
                    onMarkAsRead = {},
                ),
                packageName = room.getString("package", DebugRoom.PACKAGE_KAKAO_TALK),
                hasMention = false,
                chatLogId = -1L,
            )
            val functionName = event.java.getDeclaredConstructor().newInstance().functionName
            project.callFunction(functionName, arrayOf(data), onError)
        }
    }

    /** What StarLight's debug room screen (app/ui/debugroom/DebugRoomFragment) uses. */
    private object DebugRoom {

        /** The app's message event, not part of PluginCore. */
        private const val MESSAGE_EVENT_CLASS = "dev.mooner.starlight.listener.event.ProjectOnMessageEvent"

        const val PACKAGE_KAKAO_TALK = "com.kakao.talk"
        const val DEFAULT_SENDER = "debug_sender"
        const val DEFAULT_ROOM = "undefined"
        const val DEFAULT_ROOM_ID = "mcp_debug_room"

        @Suppress("UNCHECKED_CAST")
        fun messageEvent(): KClass<out ProjectEvent> =
            Class.forName(MESSAGE_EVENT_CLASS, true, Session::class.java.classLoader).kotlin as KClass<out ProjectEvent>

        @Volatile
        private var blankProfile: Bitmap? = null

        /** The profile image set in the debug room, or a blank one. */
        fun profileBitmap(): Bitmap =
            GlobalConfig.category("d_user").getString("profile_image_path")
                ?.let { path -> runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }
                ?: blankProfile
                ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).also { blankProfile = it }
    }
}

internal fun LogData.toEntry(): LogEntry =
    LogEntry(type.name, tag.orEmpty(), message, millis)
