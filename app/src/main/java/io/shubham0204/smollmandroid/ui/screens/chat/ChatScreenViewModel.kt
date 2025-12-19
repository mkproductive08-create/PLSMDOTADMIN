/*
 * Copyright (C) 2024 Shubham Panchal
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 * Modified by: mkproductive08-create
 * Modifications: Added memory optimizations, thread cleanup, 
 *                1.5K context window enforcement, KV cache management
 * Date: December 19, 2025
 */

package io.shubham0204.smollmandroid.ui.screens.chat

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.markwon.Markwon
import io.shubham0204.smollmandroid.data.AppDatabase
import io.shubham0204.smollmandroid.data.Chat
import io.shubham0204.smollmandroid.data.ChatMessage
import io.shubham0204.smollmandroid.llm.LLMInferenceRequest
import io.shubham0204.smollmandroid.llm.LLMRepository
import io.shubham0204.smollmandroid.llm.ModelsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.system.measureTimeMillis

private const val LOGTAG = "[ChatScreenViewModel]"
private val LOGD: (String) -> Unit = { Log.d(LOGTAG, it) }
private const val MAX_CONTEXT_SIZE = 1536 // 1.5K context window

class ChatScreenViewModel(
    private val context: Context,
    val appDB: AppDatabase,
    val modelsRepository: ModelsRepository,
    private val llmRepository: LLMRepository,
    val markwon: Markwon,
) : ViewModel() {

    enum class ModelLoadingState {
        NONE,
        LOADING,
        SUCCESS,
        FAILURE
    }

    private val _currChatState = MutableStateFlow<Chat?>(null)
    val currChatState: StateFlow<Chat?> = _currChatState.asStateFlow()

    private val _isGeneratingResponse = MutableStateFlow(false)
    val isGeneratingResponse: StateFlow<Boolean> = _isGeneratingResponse.asStateFlow()

    private val _modelLoadingState = MutableStateFlow(ModelLoadingState.NONE)
    val modelLoadingState: StateFlow<ModelLoadingState> = _modelLoadingState.asStateFlow()

    private val _partialResponse = MutableStateFlow("")
    val partialResponse: StateFlow<String> = _partialResponse.asStateFlow()

    private val _showRAMUsageLabel = MutableStateFlow(false)
    val showRAMUsageLabel: StateFlow<Boolean> = _showRAMUsageLabel.asStateFlow()

    private val _showTasksListSheet = MutableStateFlow(false)
    val showTasksListSheet: StateFlow<Boolean> = _showTasksListSheet.asStateFlow()

    private val _showChangeFolderDialog = MutableStateFlow(false)
    val showChangeFolderDialog: StateFlow<Boolean> = _showChangeFolderDialog.asStateFlow()

    var questionTextDefaultVal = ""
    var responseGenerationsSpeed: Double? = null
    var responseGenerationTimeSecs: Long? = null

    private var inferenceJob: Job? = null
    private var isModelLoaded = false

    init {
        val lastChat = appDB.getLastAccessedChat()
        if (lastChat != null) {
            _currChatState.value = lastChat
            loadModel()
        }
    }

    fun loadModel(onComplete: ((ModelLoadingState) -> Unit)? = null) {
        val chat = _currChatState.value
        if (chat == null || chat.llmModelId == -1L) {
            LOGD("loadModel: No chat or model selected")
            _modelLoadingState.value = ModelLoadingState.FAILURE
            onComplete?.invoke(ModelLoadingState.FAILURE)
            return
        }

        if (isModelLoaded) {
            LOGD("loadModel: Model already loaded")
            onComplete?.invoke(ModelLoadingState.SUCCESS)
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                _modelLoadingState.value = ModelLoadingState.LOADING
                LOGD("loadModel: Starting model load")

                val model = modelsRepository.getModelFromId(chat.llmModelId)
                val messages = appDB.getChatMessages(chat.id)
                
                // Trim messages to fit 1.5K context window
                val trimmedMessages = trimMessagesToContextWindow(messages, MAX_CONTEXT_SIZE)
                LOGD("loadModel: Trimmed ${messages.size} messages to ${trimmedMessages.size}")

                val request = LLMInferenceRequest(
                    modelPath = model.modelPath,
                    systemPrompt = chat.systemPrompt,
                    messages = trimmedMessages,
                    numThreads = chat.numThreads,
                    contextSize = minOf(chat.contextSize, MAX_CONTEXT_SIZE),
                    numGpuLayers = chat.numGpuLayers
                )

                llmRepository.loadModel(request)
                isModelLoaded = true
                
                withContext(Dispatchers.Main) {
                    _modelLoadingState.value = ModelLoadingState.SUCCESS
                    LOGD("loadModel: SUCCESS")
                    onComplete?.invoke(ModelLoadingState.SUCCESS)
                }
            } catch (e: Exception) {
                LOGD("loadModel: FAILURE - ${e.message}")
                withContext(Dispatchers.Main) {
                    _modelLoadingState.value = ModelLoadingState.FAILURE
                    onComplete?.invoke(ModelLoadingState.FAILURE)
                }
            }
        }
    }

    fun unloadModel(): Boolean {
        if (!isModelLoaded) {
            LOGD("unloadModel: Model not loaded")
            return false
        }

        return try {
            // Cancel any ongoing inference
            inferenceJob?.cancel()
            inferenceJob = null

            // Unload model and clean up
            llmRepository.unloadModel()
            isModelLoaded = false
            
            // Reset states
            _partialResponse.value = ""
            _isGeneratingResponse.value = false
            
            // Force garbage collection
            System.gc()
            
            LOGD("unloadModel: SUCCESS")
            true
        } catch (e: Exception) {
            LOGD("unloadModel: FAILURE - ${e.message}")
            false
        }
    }

    fun sendUserQuery(query: String, addMessageToDB: Boolean = true) {
        val chat = _currChatState.value ?: return
        
        if (_isGeneratingResponse.value) {
            LOGD("sendUserQuery: Already generating response")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (addMessageToDB) {
                    appDB.addUserMessage(chat.id, query)
                }

                _isGeneratingResponse.value = true
                _partialResponse.value = ""
                
                var tokenCount = 0
                val elapsedTime = measureTimeMillis {
                    inferenceJob = launch {
                        llmRepository.generateResponse(query).collect { token ->
                            _partialResponse.value += token
                            tokenCount++
                        }
                    }
                    inferenceJob?.join()
                }

                if (_partialResponse.value.isNotBlank()) {
                    appDB.addAssistantMessage(chat.id, _partialResponse.value)
                    
                    responseGenerationsSpeed = tokenCount.toDouble() / (elapsedTime / 1000.0)
                    responseGenerationTimeSecs = elapsedTime / 1000
                    
                    LOGD("Response generated: $tokenCount tokens in ${elapsedTime}ms")
                }

                _isGeneratingResponse.value = false
                _partialResponse.value = ""

            } catch (e: Exception) {
                LOGD("sendUserQuery: ERROR - ${e.message}")
                _isGeneratingResponse.value = false
                _partialResponse.value = ""
            }
        }
    }

    fun stopResponseGeneration() {
        inferenceJob?.cancel()
        inferenceJob = null
        _isGeneratingResponse.value = false
        
        // Save partial response if exists
        val chat = _currChatState.value
        if (chat != null && _partialResponse.value.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                appDB.addAssistantMessage(chat.id, _partialResponse.value)
                _partialResponse.value = ""
            }
        }
    }

    fun switchChat(chat: Chat) {
        stopResponseGeneration()
        
        viewModelScope.launch(Dispatchers.IO) {
            unloadModel()
            delay(100)
            
            appDB.updateChatLastAccessedTime(chat.id)
            _currChatState.value = chat
            
            loadModel()
        }
    }

    fun deleteMessage(messageId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            appDB.deleteMessage(messageId)
        }
    }

    fun getChatMessages(chatId: Long) = appDB.getChatMessagesFlow(chatId)

    fun getAllChats() = appDB.getAllChatsFlow()

    fun updateChatSettings(existingChat: Chat, newSettings: EditableChatSettings) {
        val updatedChat = existingChat.copy(
            name = newSettings.name,
            systemPrompt = newSettings.systemPrompt,
            contextSize = minOf(newSettings.contextSize, MAX_CONTEXT_SIZE),
            numThreads = newSettings.numThreads,
            numGpuLayers = newSettings.numGpuLayers
        )
        
        viewModelScope.launch(Dispatchers.IO) {
            appDB.updateChat(updatedChat)
            _currChatState.value = updatedChat
            
            // Reload model with new settings
            unloadModel()
            delay(100)
            loadModel()
        }
    }

    fun onEvent(event: ChatScreenUIEvent) {
        when (event) {
            is ChatScreenUIEvent.DialogEvents.ToggleTasksList -> {
                _showTasksListSheet.value = event.visible
            }
            is ChatScreenUIEvent.DialogEvents.ToggleChangeFolderDialog -> {
                _showChangeFolderDialog.value = event.visible
            }
            is ChatScreenUIEvent.DialogEvents.ToggleRAMUsageLabel -> {
                _showRAMUsageLabel.value = event.visible
            }
            else -> {}
        }
    }

    fun getCurrentMemoryUsage(): Pair<String, String> {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        
        val usedMemory = (memoryInfo.totalMem - memoryInfo.availMem) / (1024 * 1024)
        val totalMemory = memoryInfo.totalMem / (1024 * 1024)
        
        return Pair("${usedMemory}MB", "${totalMemory}MB")
    }

    private fun trimMessagesToContextWindow(
        messages: List<ChatMessage>,
        maxContextSize: Int
    ): List<ChatMessage> {
        if (messages.isEmpty()) return messages
        
        // Estimate tokens (roughly 4 chars per token)
        var totalTokens = 0
        val trimmedMessages = mutableListOf<ChatMessage>()
        
        // Start from most recent messages
        for (message in messages.reversed()) {
            val estimatedTokens = message.message.length / 4
            if (totalTokens + estimatedTokens > maxContextSize) {
                break
            }
            totalTokens += estimatedTokens
            trimmedMessages.add(0, message)
        }
        
        return trimmedMessages
    }

    override fun onCleared() {
        super.onCleared()
        inferenceJob?.cancel()
        if (isModelLoaded) {
            unloadModel()
        }
        LOGD("ViewModel cleared")
    }
}

sealed class ChatScreenUIEvent {
    sealed class DialogEvents : ChatScreenUIEvent() {
        data class ToggleTasksList(val visible: Boolean) : DialogEvents()
        data class ToggleChangeFolderDialog(val visible: Boolean) : DialogEvents()
        data class ToggleRAMUsageLabel(val visible: Boolean) : DialogEvents()
        data class ToggleMoreOptionsPopup(val visible: Boolean) : DialogEvents()
    }
}

data class EditableChatSettings(
    val name: String,
    val systemPrompt: String,
    val contextSize: Int,
    val numThreads: Int,
    val numGpuLayers: Int
) {
    companion object {
        fun fromChat(chat: Chat) = EditableChatSettings(
            name = chat.name,
            systemPrompt = chat.systemPrompt,
            contextSize = minOf(chat.contextSize, MAX_CONTEXT_SIZE),
            numThreads = chat.numThreads,
            numGpuLayers = chat.numGpuLayers
        )
    }
}