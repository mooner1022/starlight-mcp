package dev.mooner.starlightmcp.tool

import dev.mooner.neonjs.NeonValue
import dev.mooner.starlightmcp.script.ScriptValues
import kotlinx.serialization.json.JsonObject

/**
 * A tool handler written in Kotlin, or in a Rhino script: `(args) => ...`, where [args] holds the arguments the
 * client sent as Maps and Lists of plain values.
 */
fun interface ToolHandler {
    fun handle(args: Any?): Any?
}

/**
 * Calls a tool handler given by a script with the client's arguments. It returns the result, or a promise of it in
 * NeonJS: text for strings, numbers and booleans, JSON text for objects and arrays, or an object with a `content`
 * array to send an MCP tool result as is. Throwing makes the call fail with the error message.
 */
fun interface ScriptFunction {
    fun call(arguments: JsonObject): Any?

    companion object {

        private const val RHINO_CONTEXT = "org.mozilla.javascript.Context"

        /**
         * Wraps what a script passed as a handler.
         *
         * NeonJS functions get the arguments as real JS objects and arrays, parsed by the script's own `JSON.parse`;
         * host Maps and Lists would lack array methods such as `map`. Rhino functions are turned into a [ToolHandler]
         * by Rhino itself, which needs the Rhino context of the calling script.
         */
        fun of(handler: Any?): ScriptFunction =
            when {
                handler is NeonValue && handler.isFunction -> ScriptFunction { arguments ->
                    val json = handler.context.global.getMember("JSON")
                    handler.call(json.callMember("parse", arguments.toString()))
                }
                handler is ToolHandler -> ScriptFunction { arguments ->
                    handler.handle(ScriptValues.fromJson(arguments))
                }
                handler != null && isRhinoFunction(handler) -> of(toRhinoHandler(handler))
                else -> throw IllegalArgumentException("Tool handler must be a function")
            }

        private fun isRhinoFunction(value: Any): Boolean =
            runCatching { Class.forName("org.mozilla.javascript.Function", false, value.javaClass.classLoader).isInstance(value) }
                .getOrDefault(false)

        private fun toRhinoHandler(function: Any): ToolHandler {
            val context = Class.forName(RHINO_CONTEXT, false, function.javaClass.classLoader)
            val jsToJava = context.getMethod("jsToJava", Any::class.java, Class::class.java)
            return jsToJava.invoke(null, function, ToolHandler::class.java) as ToolHandler
        }
    }
}
