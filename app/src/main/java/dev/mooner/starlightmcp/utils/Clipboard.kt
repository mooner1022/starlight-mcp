package dev.mooner.starlightmcp.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import dev.mooner.starlightmcp.McpRuntime

/** Copies the server's first endpoint URL, if it is running. */
fun copyServerUrl(context: Context) {
    val url = McpRuntime.serverInfo().urls.firstOrNull() ?: return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("URL", url))
    Toast.makeText(context, tr("Copied $url", "$url 복사됨"), Toast.LENGTH_SHORT).show()
}
