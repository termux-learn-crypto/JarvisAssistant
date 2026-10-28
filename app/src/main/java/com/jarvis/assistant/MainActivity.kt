package com.jarvis.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.agent.JarvisAgent
import com.jarvis.assistant.model.ActionPlan
import com.jarvis.assistant.ui.theme.JarvisTheme
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var jarvisAgent: JarvisAgent
    private lateinit var actionExecutor: ActionExecutor
    
    private val permissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.POST_NOTIFICATIONS
    )
    
    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val matches = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!matches.isNullOrEmpty()) {
                val spokenText = matches[0]
                processCommand(spokenText)
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        jarvisAgent = JarvisAgent(this)
        actionExecutor = ActionExecutor(this)
        
        requestPermissions()
        
        setContent {
            JarvisTheme {
                JarvisScreen()
            }
        }
    }
    
    private fun requestPermissions() {
        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), 100)
        }
    }
    
    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Jarvis sun raha hai...")
        }
        try {
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun processCommand(command: String) {
        lifecycleScope.launch {
            val plan = jarvisAgent.processCommand(command)
            val reply = actionExecutor.executePlan(plan)
            // Update UI with reply
        }
    }
    
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun JarvisScreen() {
        var messageText by remember { mutableStateOf("") }
        var messages by remember { mutableStateOf(listOf<Pair<String, Boolean>>()) }
        
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Jarvis", fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF0D1B2A),
                        titleContentColor = Color.Cyan
                    ),
                    actions = {
                        IconButton(onClick = { }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                        }
                    }
                )
            },
            containerColor = Color(0xFF000814)
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(16.dp),
                    reverseLayout = true
                ) {
                    items(messages.reversed()) { message ->
                        MessageBubble(message.first, message.second)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .background(Color(0xFF001D3D), RoundedCornerShape(24.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Kuch bhi pucho...", color = Color.Gray) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        )
                    )
                    IconButton(
                        onClick = { 
                            if (messageText.isNotEmpty()) {
                                messages = messages + Pair(messageText, true)
                                processCommand(messageText)
                                messageText = ""
                            }
                        }
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send", tint = Color.Cyan)
                    }
                    IconButton(
                        onClick = { startVoiceInput() },
                        modifier = Modifier
                            .background(Color.Cyan, CircleShape)
                            .size(48.dp)
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = "Mic", tint = Color.Black)
                    }
                }
            }
        }
    }
    
    @Composable
    fun MessageBubble(text: String, isUser: Boolean) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
        ) {
            Card(
                modifier = Modifier.widthIn(max = 280.dp),
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 16.dp
                ),
                colors = CardDefaults.cardColors(
                    containerColor = if (isUser) Color(0xFF003566) else Color(0xFF001D3D)
                )
            ) {
                Text(
                    text = text,
                    modifier = Modifier.padding(12.dp),
                    color = Color.White,
                    fontSize = 14.sp
                )
            }
        }
    }
}
