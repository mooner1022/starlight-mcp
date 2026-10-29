package dev.mooner.starlightmcp.tool

import kotlinx.coroutines.CoroutineDispatcher

/** The project a tool belongs to, as the server sees it. */
interface ToolOwner {

    val id: String

    val name: String

    /** Whether tools may be called now; tools of inactive owners are listed but fail when called. */
    val isActive: Boolean

    /** Where the owner's handlers run. */
    val dispatcher: CoroutineDispatcher

    fun logInfo(message: String)

    fun logError(message: String)
}
