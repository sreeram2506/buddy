package com.sreeram.jarvis.jobs

import org.json.JSONArray
import org.json.JSONObject

// The agent keeps its conversation in Gemini's format. These functions turn it into the OpenAI-style format Groq speaks, and the reply back,
// so either provider can answer any turn. Pure functions, tested without a network.
object OpenAiBridge {
    // Gemini declares types as "OBJECT"/"STRING"; OpenAI wants lower case, and a function with no parameters still needs an empty object schema.
    private fun lower(v: Any?): Any? = when (v) {
        is JSONObject -> JSONObject().also { o -> v.keys().forEach { k -> o.put(k, if (k == "type") (v.get(k) as? String)?.lowercase() ?: lower(v.get(k)) else lower(v.get(k))) } }
        is JSONArray -> JSONArray().also { a -> for (i in 0 until v.length()) a.put(lower(v.get(i))) }
        else -> v
    }

    fun tools(decls: JSONArray): JSONArray = JSONArray().also { out ->
        for (i in 0 until decls.length()) {
            val d = decls.getJSONObject(i)
            val params = d.optJSONObject("parameters")?.let { lower(it) as JSONObject } ?: JSONObject().put("type", "object").put("properties", JSONObject())
            out.put(JSONObject().put("type", "function").put("function", JSONObject().put("name", d.getString("name")).put("description", d.optString("description")).put("parameters", params)))
        }
    }

    // Groq's free tier allows few tokens a minute, so only the recent turns are sent. The tail starts on a model turn so every tool result
    // still follows the call that asked for it.
    fun messages(system: String, contents: JSONArray, keepLast: Int = 12): JSONArray {
        var start = maxOf(0, contents.length() - keepLast)
        while (start < contents.length() && contents.getJSONObject(start).optString("role") != "model") start++
        val out = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        out.put(JSONObject().put("role", "user").put("content", if (start == 0) "Start the job hunt." else "Continue the job hunt. Earlier steps are summarised by the counts in the tool results."))
        var n = 0
        val pending = ArrayDeque<String>() // ids of calls whose results have not been given yet
        for (i in start until contents.length()) {
            val c = contents.getJSONObject(i); val parts = c.optJSONArray("parts") ?: JSONArray()
            if (c.optString("role") == "model") {
                val text = (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }
                val calls = JSONArray()
                for (k in 0 until parts.length()) parts.getJSONObject(k).optJSONObject("functionCall")?.let { fc ->
                    val id = "call_${n++}"; pending.addLast(id)
                    calls.put(JSONObject().put("id", id).put("type", "function").put("function", JSONObject().put("name", fc.getString("name")).put("arguments", (fc.optJSONObject("args") ?: JSONObject()).toString())))
                }
                out.put(JSONObject().put("role", "assistant").put("content", if (text.isEmpty()) JSONObject.NULL else text).apply { if (calls.length() > 0) put("tool_calls", calls) })
            } else {
                for (k in 0 until parts.length()) {
                    val p = parts.getJSONObject(k)
                    p.optJSONObject("functionResponse")?.let { fr ->
                        val id = pending.removeFirstOrNull() ?: return@let
                        out.put(JSONObject().put("role", "tool").put("tool_call_id", id).put("content", fr.optJSONObject("response")?.optString("result").orEmpty()))
                    } ?: p.optString("text").takeIf { it.isNotEmpty() }?.let { out.put(JSONObject().put("role", "user").put("content", it)) }
                }
            }
        }
        return out
    }

    // Gemini 3 wants a thought signature on every function call in the history. Calls made by Groq have none, so they get the documented bypass value
    // (the same one Buddy's chat uses). Returns a copy; the stored conversation is left as it was.
    fun withSignatures(contents: JSONArray): JSONArray {
        val copy = JSONArray(contents.toString())
        for (i in 0 until copy.length()) {
            val parts = copy.getJSONObject(i).optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val part = parts.getJSONObject(j)
                if (part.has("functionCall") && !part.has("thoughtSignature")) part.put("thoughtSignature", "skip_thought_signature_validator")
            }
        }
        return copy
    }

    // An OpenAI-style reply as a Gemini-style turn, so the rest of the agent does not care which provider answered.
    fun toGemini(message: JSONObject): JSONObject {
        val parts = JSONArray()
        message.optString("content").takeIf { it.isNotEmpty() && it != "null" }?.let { parts.put(JSONObject().put("text", it)) }
        val calls = message.optJSONArray("tool_calls")
        for (i in 0 until (calls?.length() ?: 0)) {
            val f = calls!!.getJSONObject(i).getJSONObject("function")
            val args = runCatching { JSONObject(f.optString("arguments").ifBlank { "{}" }) }.getOrDefault(JSONObject())
            parts.put(JSONObject().put("functionCall", JSONObject().put("name", f.getString("name")).put("args", args)))
        }
        return JSONObject().put("role", "model").put("parts", parts)
    }
}
