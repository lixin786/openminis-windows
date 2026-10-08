package com.openminis.app.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Provider-agnostic tool definition. Each tool registers with this structure,
 * and providers convert it to their native format (Anthropic input_schema,
 * Gemini function_declarations, OpenAI function calling).
 */
data class AgentToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, AgentToolParam>,
    val required: List<String> = emptyList(),
    val propertyOrdering: List<String>? = null,
) {
    /** Anthropic format: {name, description, input_schema: {type:object, properties, required}} */
    fun toAnthropicJson(): JSONObject {
        val props = JSONObject()
        for ((key, param) in parameters) {
            props.put(key, param.toJson())
        }
        val schema = JSONObject().apply {
            put("type", "object")
            put("properties", props)
            if (required.isNotEmpty()) put("required", JSONArray(required))
        }
        return JSONObject().apply {
            put("name", name)
            put("description", description)
            put("input_schema", schema)
        }
    }

    /** Gemini format: {name, description, parameters: {type:OBJECT, properties, required}} */
    fun toGeminiJson(): JSONObject {
        val props = JSONObject()
        for ((key, param) in parameters) {
            props.put(key, param.toGeminiJson())
        }
        val params = JSONObject().apply {
            put("type", "OBJECT")
            put("properties", props)
            if (required.isNotEmpty()) put("required", JSONArray(required))
            if (propertyOrdering != null) put("propertyOrdering", JSONArray(propertyOrdering))
        }
        return JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", params)
        }
    }

    /** OpenAI format: {type:function, function: {name, description, parameters: {type:object, ...}}} */
    fun toOpenAIJson(): JSONObject {
        val props = JSONObject()
        for ((key, param) in parameters) {
            props.put(key, param.toJson())
        }
        val params = JSONObject().apply {
            put("type", "object")
            put("properties", props)
            if (required.isNotEmpty()) put("required", JSONArray(required))
        }
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", description)
                put("parameters", params)
            })
        }
    }
}

data class AgentToolParam(
    val type: String,
    val description: String,
    val enumValues: List<String>? = null,
    /**
     * [desktop P1-1] A full JSON Schema for this property (MCP tools publish nested objects / arrays).
     * When set it is sent as-is to OpenAI / Anthropic and reduced to Gemini's schema subset.
     */
    val rawSchema: JSONObject? = null,
) {
    fun toJson(): JSONObject = rawSchema?.let { JsonSchemaCompat.forOpenAI(it, description) } ?: JSONObject().apply {
        put("type", type)
        put("description", description)
        if (enumValues != null) put("enum", JSONArray(enumValues))
    }

    fun toGeminiJson(): JSONObject = rawSchema?.let { JsonSchemaCompat.forGemini(it, description) } ?: JSONObject().apply {
        put("type", type.uppercase())
        put("description", description)
        if (enumValues != null) put("enum", JSONArray(enumValues))
    }
}

/** [desktop P1-1] Makes third-party (MCP) JSON Schemas acceptable to each provider. */
object JsonSchemaCompat {
    /** OpenAI / Anthropic: keep the schema, but every array needs `items` and a description is added when missing. */
    fun forOpenAI(schema: JSONObject, description: String): JSONObject {
        val o = JSONObject(schema.toString())
        if (description.isNotBlank() && !o.has("description")) o.put("description", description)
        fixArrays(o, 0)
        return o
    }

    private fun fixArrays(o: JSONObject, depth: Int) {
        if (depth > 32) return
        if (o.optString("type") == "array" && !o.has("items")) o.put("items", JSONObject())
        o.optJSONObject("items")?.let { fixArrays(it, depth + 1) }
        o.optJSONObject("properties")?.let { p -> p.keys().forEach { k -> p.optJSONObject(k)?.let { fixArrays(it, depth + 1) } } }
        for (key in listOf("anyOf", "oneOf", "allOf")) o.optJSONArray(key)?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { fixArrays(it, depth + 1) } }
    }

    /**
     * Gemini accepts an OpenAPI subset: type (upper case), format, description, nullable, enum,
     * properties, required, items. Unions collapse to their first non-null branch; a list of types
     * to its first non-"null" entry (+ nullable).
     */
    fun forGemini(schema: JSONObject, description: String = ""): JSONObject = reduce(schema, description, 0)

    private fun reduce(src: JSONObject, description: String, depth: Int): JSONObject {
        var s = src
        for (key in listOf("anyOf", "oneOf", "allOf")) {
            val a = s.optJSONArray(key) ?: continue
            val pick = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.firstOrNull { it.optString("type") != "null" }
            if (pick != null) s = JSONObject(pick.toString()).also { m -> if (!m.has("description") && src.has("description")) m.put("description", src.optString("description")) }
            break
        }
        val out = JSONObject()
        var nullable = false
        val type: String = when (val t = s.opt("type")) {
            is String -> t
            is JSONArray -> {
                val types = (0 until t.length()).map { t.optString(it) }
                nullable = "null" in types
                types.firstOrNull { it != "null" } ?: "string"
            }
            else -> if (s.has("properties")) "object" else "string"
        }
        out.put("type", type.uppercase())
        val desc = s.optString("description", "").ifBlank { description }
        if (desc.isNotBlank()) out.put("description", desc)
        if (nullable || s.optBoolean("nullable", false)) out.put("nullable", true)
        s.optString("format", "").takeIf { it.isNotEmpty() && type == "string" && it in setOf("enum", "date-time") }?.let { out.put("format", it) }
        s.optJSONArray("enum")?.let { e -> out.put("enum", JSONArray((0 until e.length()).map { e.opt(it).toString() })) }
        if (depth < 32) {
            if (type == "object") {
                s.optJSONObject("properties")?.let { p ->
                    val props = JSONObject()
                    p.keys().forEach { k -> p.optJSONObject(k)?.let { props.put(k, reduce(it, "", depth + 1)) } }
                    if (props.length() > 0) out.put("properties", props)
                }
                s.optJSONArray("required")?.let { out.put("required", it) }
            }
            if (type == "array") out.put("items", s.optJSONObject("items")?.let { reduce(it, "", depth + 1) } ?: JSONObject().put("type", "STRING"))
        }
        return out
    }
}
