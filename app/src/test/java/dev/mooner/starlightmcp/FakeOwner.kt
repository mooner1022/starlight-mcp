package dev.mooner.starlightmcp

import dev.mooner.starlightmcp.tool.ToolOwner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.util.Collections

class FakeOwner(
    override val id: String = "project",
    override val name: String = id,
) : ToolOwner {

    @Volatile
    override var isActive: Boolean = true

    override val dispatcher: CoroutineDispatcher = Dispatchers.IO

    val errors: MutableList<String> = Collections.synchronizedList(arrayListOf())

    override fun logInfo(message: String) {}

    override fun logError(message: String) {
        errors += message
    }
}
