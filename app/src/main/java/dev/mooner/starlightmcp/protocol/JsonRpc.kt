package dev.mooner.starlightmcp.protocol

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object JsonRpc {

    const val VERSION = "2.0"

    const val PARSE_ERROR      = -32700
    const val INVALID_REQUEST  = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS   = -32602
    const val INTERNAL_ERROR   = -32603

    fun result(id: JsonElement, result: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", VERSION)
        put("id", id)
        put("result", result)
    }

    fun error(id: JsonElement?, code: Int, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", VERSION)
        put("id", id ?: JsonNull)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }
}

/** A request failed with a JSON-RPC error. */
class JsonRpcException(val code: Int, message: String) : Exception(message)
