package com.vilas.jarvis

import org.json.JSONObject

data class Action(val tool: String, val params: JSONObject)

data class Plan(
    val actions: List<Action>,
    val confirmationRequired: Boolean,
    val reply: String
) {
    companion object {
        private val SENSITIVE = setOf("send_sms", "make_call", "screen_action")

        fun parse(raw: String): Plan {
            // Kabhi model ```json ... ``` bhej de to saaf karo
            var text = raw.trim()
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start >= 0 && end > start) text = text.substring(start, end + 1)

            val json = JSONObject(text)
            val arr = json.optJSONArray("actions")
            val list = mutableListOf<Action>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val a = arr.getJSONObject(i)
                    list.add(Action(a.optString("tool"), a.optJSONObject("parameters") ?: JSONObject()))
                }
            }
            // Safety: sensitive action ho to confirmation hamesha true
            val confirm = json.optBoolean("confirmationRequired", false) ||
                list.any { it.tool in SENSITIVE }
            return Plan(list, confirm, json.optString("reply", ""))
        }
    }

    fun describe(): String = actions.joinToString("\n") { a ->
        "• " + a.tool + " " + a.params.toString()
    }
}
