package com.jarvis.assistant.model

import com.google.gson.annotations.SerializedName

data class ActionPlan(
    @SerializedName("actions")
    val actions: List<Action> = emptyList(),
    
    @SerializedName("confirmationRequired")
    val confirmationRequired: Boolean = false,
    
    @SerializedName("reply")
    val reply: String = ""
)

data class Action(
    @SerializedName("tool")
    val tool: String,
    
    @SerializedName("parameters")
    val parameters: Map<String, Any> = emptyMap()
)
