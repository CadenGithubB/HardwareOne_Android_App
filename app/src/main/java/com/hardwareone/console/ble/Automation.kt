package com.hardwareone.console.ble

import org.json.JSONObject

/**
 * One automation from `automationlist json` (the raw `/system/automations.json`, same blob the
 * web's `/api/automations` serves). An automation = trigger(s) + optional condition → run
 * command(s). Tier 1 (this model) is read + control only; the trigger/command builder is future.
 */
data class Automation(
    val id: Long,
    val name: String,
    val enabled: Boolean,
    val commands: List<String>,
    val triggerSummary: String,
    val condition: String,
)

/** `automationlist json` → `{ "automations": [ … ] }`, or `{ "error": … }` on read failure. */
data class AutomationList(val automations: List<Automation>, val error: String?) {
    companion object {
        fun parse(json: String): AutomationList? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            if (o.has("error")) return AutomationList(emptyList(), o.optString("error").ifEmpty { "error" })
            val arr = o.optJSONArray("automations") ?: return AutomationList(emptyList(), null)
            val list = buildList {
                for (i in 0 until arr.length()) {
                    val a = arr.optJSONObject(i) ?: continue
                    // Action: firmware stores `commands` (array) or a single `command`.
                    val cmds = buildList {
                        a.optJSONArray("commands")?.let { c -> for (j in 0 until c.length()) add(c.optString(j)) }
                        if (isEmpty()) a.optString("command").takeIf { it.isNotEmpty() }?.let { add(it) }
                    }
                    add(
                        Automation(
                            id = a.optLong("id"),
                            name = a.optString("name").ifEmpty { "(unnamed)" },
                            enabled = a.optBoolean("enabled"),
                            commands = cmds,
                            triggerSummary = summarizeTriggers(a.optJSONArray("triggers")),
                            condition = a.optString("condition"),
                        ),
                    )
                }
            }
            return AutomationList(list, null)
        }

        private fun summarizeTriggers(arr: org.json.JSONArray?): String {
            if (arr == null || arr.length() == 0) return "—"
            return (0 until arr.length()).mapNotNull { i ->
                val t = arr.optJSONObject(i) ?: return@mapNotNull null
                when (val type = t.optString("type")) {
                    "time" -> {
                        val time = t.optString("time")
                        val days = t.optString("days")
                        "at $time" + if (days.isNotEmpty()) " ($days)" else ""
                    }
                    "interval" -> "interval"
                    "manual" -> "manual"
                    "boot" -> "on boot"
                    else -> type.ifEmpty { "?" }
                }
            }.joinToString(", ").ifEmpty { "—" }
        }
    }
}
