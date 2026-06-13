package com.hardwareone.console.ble

import org.json.JSONObject

/**
 * Parsed `llmstatus json` reply — the on-device LLM engine state.
 * `{"v":1,"state":"READY","model":"tiny.bin","tokPerSec":12.4,"error":""}`
 * state ∈ UNLOADED | LOADING | READY | GENERATING | ERROR.
 */
data class LlmStatus(
    val state: String,
    val model: String,
    val tokPerSec: Double,
    val error: String,
) {
    val ready: Boolean get() = state.equals("READY", ignoreCase = true)
    val loading: Boolean get() = state.equals("LOADING", ignoreCase = true)
    val generating: Boolean get() = state.equals("GENERATING", ignoreCase = true)
    val errored: Boolean get() = state.equals("ERROR", ignoreCase = true)
    /** A model is in memory (ready or actively generating). */
    val loaded: Boolean get() = ready || generating

    companion object {
        fun parse(json: String): LlmStatus? {
            val o = try {
                JSONObject(json)
            } catch (_: Exception) {
                return null
            }
            if (!o.has("state")) return null
            return LlmStatus(
                state = o.optString("state", "UNLOADED"),
                model = o.optString("model"),
                tokPerSec = o.optDouble("tokPerSec", 0.0),
                error = o.optString("error"),
            )
        }

        /** Parse `llmmodels json` → the list of available model files. */
        fun parseModels(json: String): List<String>? {
            val o = try {
                JSONObject(json)
            } catch (_: Exception) {
                return null
            }
            val arr = o.optJSONArray("models") ?: return null
            return buildList { for (i in 0 until arr.length()) add(arr.optString(i)) }
                .filter { it.isNotEmpty() }
        }
    }
}

/**
 * Parsed `llmresult json [offset]` reply — incremental generated text since `offset`.
 * `{"v":1,"text":"…","done":false,"len":120}` (`text` = bytes since offset; `len` = total).
 */
data class LlmResult(val text: String, val done: Boolean, val len: Int) {
    companion object {
        fun parse(json: String): LlmResult? {
            val o = try {
                JSONObject(json)
            } catch (_: Exception) {
                return null
            }
            if (!o.has("text") && !o.has("done")) return null
            return LlmResult(o.optString("text"), o.optBoolean("done", false), o.optInt("len", 0))
        }
    }
}

/** One chat message in the LLM conversation. */
data class ChatMessage(val role: Role, val text: String) {
    enum class Role { USER, ASSISTANT }
}
