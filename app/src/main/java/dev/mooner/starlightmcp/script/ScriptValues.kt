package dev.mooner.starlightmcp.script

import dev.mooner.neonjs.NeonValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Converts values handed over by scripts, for both engines:
 * - NeonJS gives primitives as Kotlin values and anything else as a [NeonValue].
 * - Rhino gives JS objects as `java.util.Map`, arrays as `java.util.List`, and `undefined` as its Undefined (or the
 *   string "undefined" where an Object is returned).
 */
object ScriptValues {

    private const val RHINO_UNDEFINED = "org.mozilla.javascript.Undefined"
    private const val RHINO_WRAPPER = "org.mozilla.javascript.Wrapper"
    private const val RHINO_FUNCTION = "org.mozilla.javascript.Function"
    private const val MAX_DEPTH = 64
    private const val TO_JSON = "toJSON"

    fun isAbsent(value: Any?): Boolean =
        value == null || value is Unit || value.javaClass.name == RHINO_UNDEFINED || (value is NeonValue && value.isNullish)

    /** JS `undefined`, which JSON leaves out of objects, unlike `null`. */
    private fun isUndefined(value: Any?): Boolean =
        value is Unit || (value != null && value.javaClass.name == RHINO_UNDEFINED) || (value is NeonValue && value.isUndefined)

    /** [isAbsent], also counting what Rhino returns for `undefined` from a handler. */
    fun isAbsentResult(value: Any?): Boolean =
        isAbsent(value) || value == "undefined"

    fun isPromise(value: Any?): Boolean =
        value is NeonValue && value.isPromise

    /** Waits for a NeonJS promise, running the context's jobs meanwhile. Throws if it is rejected or still pending. */
    fun await(value: Any?, timeoutMillis: Long): Any? =
        if (value is NeonValue && value.isPromise) value.await(timeoutMillis.coerceAtLeast(1)) else value

    /** The text of a string, number or boolean; null for other values. */
    fun primitiveText(value: Any?): String? {
        val value = unwrap(value)
        return if (value is CharSequence || value is Number || value is Boolean) toText(value) else null
    }

    /**
     * Arguments sent by a client as values scripts can read: objects become Maps, arrays Lists, integers Ints (or
     * Doubles when they don't fit) and other numbers Doubles.
     */
    fun fromJson(element: JsonElement): Any? =
        when (element) {
            JsonNull         -> null
            is JsonObject    -> element.mapValuesTo(linkedMapOf()) { (_, value) -> fromJson(value) }
            is JsonArray     -> element.mapTo(arrayListOf(), ::fromJson)
            is JsonPrimitive -> when {
                element.isString -> element.content
                element.content == "true" || element.content == "false" -> element.content.toBooleanStrict()
                else -> element.content.toIntOrNull() ?: element.content.toDouble()
            }
        }

    fun toText(value: Any?): String =
        when (val value = unwrap(value)) {
            null       -> ""
            is Double  -> formatNumber(value)
            is Float   -> formatNumber(value.toDouble())
            else       -> if (isAbsent(value)) "" else value.toString()
        }

    fun toJson(value: Any?): String =
        toJsonElement(value).toString()

    /** The value as JSON, the way JSON.stringify would write it. */
    fun toJsonElement(value: Any?): JsonElement =
        toJsonElement(value, 0)

    private fun toJsonElement(value: Any?, depth: Int): JsonElement {
        check(depth < MAX_DEPTH) { "Value is nested too deeply to convert to JSON" }
        return when (val value = unwrap(value)) {
            null, is Unit    -> JsonNull
            is NeonValue     -> neonToJsonElement(value, depth)
            is Boolean       -> JsonPrimitive(value)
            is Double        -> if (value.isFinite()) JsonPrimitive(numberOf(value)) else JsonNull
            is Float         -> toJsonElement(value.toDouble(), depth)
            is Number        -> JsonPrimitive(value)
            is CharSequence  -> JsonPrimitive(value.toString())
            is Char          -> JsonPrimitive(value.toString())
            is JsonElement   -> value
            is Map<*, *>     -> JsonObject(
                value.entries
                    .filterNot { (_, v) -> isUndefined(v) || isFunction(v) }
                    .associate { (k, v) -> k.toString() to toJsonElement(v, depth + 1) }
            )
            is Iterable<*>   -> JsonArray(value.map { toJsonElement(if (isAbsent(it)) null else it, depth + 1) })
            is Array<*>      -> toJsonElement(value.asList(), depth)
            is IntArray      -> toJsonElement(value.asList(), depth)
            is LongArray     -> toJsonElement(value.asList(), depth)
            is DoubleArray   -> toJsonElement(value.asList(), depth)
            is BooleanArray  -> toJsonElement(value.asList(), depth)
            else             -> if (isAbsent(value)) JsonNull else JsonPrimitive(value.toString())
        }
    }

    /**
     * A JS object or array the way JSON.stringify sees it (toJSON() is honored, so Dates become ISO strings), except
     * that host objects inside, such as lists the plugin handed over, are converted as host values.
     */
    private fun neonToJsonElement(value: NeonValue, depth: Int): JsonElement {
        if (value.isFunction || value.isSymbol)
            return JsonNull
        if (value.hasMember(TO_JSON) && value.getMember(TO_JSON).isFunction)
            return toJsonElement(value.callMember(TO_JSON), depth + 1)
        if (value.isArray)
            return JsonArray(List(value.arraySize.toInt()) { i ->
                toJsonElement(value.getElement(i.toLong()).takeUnless(::isAbsent), depth + 1)
            })
        if (!value.isObject)
            return JsonPrimitive(value.toString())
        val entries: MutableMap<String, JsonElement> = linkedMapOf()
        for (key in value.memberKeys()) {
            val member = value.getMember(key)
            if (!isUndefined(member) && !isFunction(member))
                entries[key] = toJsonElement(member, depth + 1)
        }
        return JsonObject(entries)
    }

    /** Primitives inside a [NeonValue] or a Rhino wrapper as Kotlin values; other values as they are. */
    private fun unwrap(value: Any?): Any? =
        when {
            value is NeonValue -> when {
                value.isNullish    -> null
                value.isBoolean    -> value.asBoolean()
                value.isNumber     -> value.asDouble()
                value.isString     -> value.asString()
                value.isBigInt     -> value.asBigInteger()
                value.isHostObject -> value.asHostObject<Any?>()
                else               -> value
            }
            value != null && value.javaClass.implements(RHINO_WRAPPER) ->
                value.javaClass.getMethod("unwrap").invoke(value)
            else -> value
        }

    private fun isFunction(value: Any?): Boolean =
        (value is NeonValue && value.isFunction) || (value != null && value.javaClass.implements(RHINO_FUNCTION))

    private fun Class<*>.implements(name: String): Boolean {
        var cls: Class<*>? = this
        while (cls != null) {
            if (cls.interfaces.any { it.name == name || it.implements(name) })
                return true
            cls = cls.superclass
        }
        return false
    }

    private fun numberOf(value: Double): Number =
        if (value % 1.0 == 0.0 && value in LONG_RANGE) value.toLong() else value

    private fun formatNumber(value: Double): String =
        numberOf(value).toString()

    // Integers JS numbers hold exactly.
    private val LONG_RANGE = -9.007199254740991E15..9.007199254740991E15
}
