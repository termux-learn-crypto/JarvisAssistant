package com.jarvis.assistant.agent

import android.content.Context
import com.google.gson.Gson
import com.jarvis.assistant.model.ActionPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class JarvisAgent(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    
    suspend fun processCommand(userMessage: String): ActionPlan = withContext(Dispatchers.IO) {
        try {
            // Using Google Gemini API - user needs to add their key
            // For now, using a simple approach with local parsing
            val apiKey = getApiKey()
            if (apiKey.isEmpty()) {
                return@withContext ActionPlan(
                    actions = emptyList(),
                    confirmationRequired = false,
                    reply = "API key set nahi hai. Settings mein Gemini API key add karo."
                )
            }
            
            val systemPrompt = JarvisPrompt.getSystemPrompt()
            
            val jsonBody = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", "$systemPrompt\n\nUser: $userMessage")
                            })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.1)
                    put("topK", 1)
                    put("topP", 0.8)
                    put("maxOutputTokens", 1024)
                })
            }
            
            val requestBody = jsonBody.toString()
                .toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey")
                .post(requestBody)
                .build()
            
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            
            val jsonResponse = JSONObject(responseBody)
            val candidates = jsonResponse.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val content = candidates.getJSONObject(0).optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    val text = parts.getJSONObject(0).optString("text", "")
                    // Extract JSON from response
                    val jsonStart = text.indexOf("{")
                    val jsonEnd = text.lastIndexOf("}") + 1
                    if (jsonStart >= 0 && jsonEnd > jsonStart) {
                        val jsonStr = text.substring(jsonStart, jsonEnd)
                        return@withContext gson.fromJson(jsonStr, ActionPlan::class.java)
                    }
                }
            }
            
            ActionPlan(
                actions = emptyList(),
                confirmationRequired = false,
                reply = "Kuch samajh nahi aaya. Dubara try karo."
            )
        } catch (e: Exception) {
            ActionPlan(
                actions = emptyList(),
                confirmationRequired = false,
                reply = "Error: ${e.message}"
            )
        }
    }
    
    private fun getApiKey(): String {
        val prefs = context.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
        return prefs.getString("gemini_api_key", "") ?: ""
    }
}
