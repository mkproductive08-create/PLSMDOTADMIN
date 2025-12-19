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
 */

package io.shubham0204.smollmandroid.ui.screens.chat

import CustomNavTypes
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Spanned
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import compose.icons.FeatherIcons
import compose.icons.feathericons.Menu
import compose.icons.feathericons.MoreVertical
import compose.icons.feathericons.Send
import compose.icons.feathericons.StopCircle
import compose.icons.feathericons.User
import io.shubham0204.smollmandroid.R
import io.shubham0204.smollmandroid.data.Chat
import io.shubham0204.smollmandroid.data.Task
import io.shubham0204.smollmandroid.ui.components.AppBarTitleText
import io.shubham0204.smollmandroid.ui.components.MediumLabelText
import io.shubham0204.smollmandroid.ui.components.SelectModelsList
import io.shubham0204.smollmandroid.ui.components.TasksList
import io.shubham0204.smollmandroid.ui.components.TextFieldDialog
import io.shubham0204.smollmandroid.ui.screens.chat.ChatScreenViewModel.ModelLoadingState
import io.shubham0204.smollmandroid.ui.screens.chat.dialogs.ChangeFolderDialogUI
import io.shubham0204.smollmandroid.ui.screens.chat.dialogs.ChatMessageOptionsDialog
import io.shubham0204.smollmandroid.ui.screens.chat.dialogs.ChatMoreOptionsPopup
import io.shubham0204.smollmandroid.ui.screens.chat.dialogs.createChatMessageOptionsDialog
import io.shubham0204.smollmandroid.ui.screens.manage_tasks.ManageTasksActivity
import io.shubham0204.smollmandroid.ui.theme.SmolLMAndroidTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.android.ext.android.inject
import kotlin.reflect.typeOf

private const val LOGTAG = "[ChatActivity-Kt]"
private val LOGD: (String) -> Unit = { Log.d(LOGTAG, it) }

@Serializable
object ChatRoute

@Serializable
data class EditChatSettingsRoute(val chat: Chat, val modelContextSize: Int)

class ChatActivity : ComponentActivity() {
    private val viewModel: ChatScreenViewModel by inject()
    private var modelUnloaded = false
    private var isModelLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                val chatCount = viewModel.appDB.getChatsCount()
                val newChat = viewModel.appDB.addChat(chatName = "Untitled ${chatCount + 1}")
                viewModel.switchChat(newChat)
                viewModel.questionTextDefaultVal = text
            }
        }

        if (intent?.action == Intent.ACTION_VIEW && intent.getLongExtra("task_id", 0L) != 0L) {
            val taskId = intent.getLongExtra("task_id", 0L)
            viewModel.appDB.getTask(taskId)?.let { task -> createChatFromTask(viewModel, task) }
        }

        setContent {
            val navController = rememberNavController()
            Box(modifier = Modifier.safeDrawingPadding()) {
                NavHost(
                    navController = navController,
                    startDestination = ChatRoute,
                    enterTransition = { fadeIn() },
                    exitTransition = { fadeOut() },
                ) {
                    composable<EditChatSettingsRoute>(
                        typeMap = mapOf(
                            typeOf<Chat>() to CustomNavTypes.ChatNavType
                        )
                    ) { backStackEntry ->
                        val route: EditChatSettingsRoute = backStackEntry.toRoute()
                        val settings = EditableChatSettings.fromChat(route.chat)
                        EditChatSettingsScreen(
                            settings,
                            route.modelContextSize,
                            onUpdateChat = {
                                viewModel.updateChatSettings(
                                    existingChat = route.chat,
                                    it
                                )
                            },
                            onBackClicked = { navController.navigateUp() },
                        )
                    }
                    composable<ChatRoute> {
                        ChatActivityScreenUI(
                            viewModel,
                            onEditChatParamsClick = { chat, modelContextSize ->
                                navController.navigate(
                                    EditChatSettingsRoute(
                                        chat,
                                        modelContextSize,
                                    )
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (modelUnloaded) {
            viewModel.loadModel()
            isModelLoaded = true
            LOGD("onStart() - model loaded")
        }
    }

    override fun onStop() {
        super.onStop()
        if (isModelLoaded && !viewModel.isGeneratingResponse.value) {
            modelUnloaded = viewModel.unloadModel()
            isModelLoaded = !modelUnloaded
            LOGD("onStop() - model unloaded: $modelUnloaded")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isModelLoaded) {
            viewModel.unloadModel()
            isModelLoaded = false
            LOGD("onDestroy() - final cleanup")
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                if (isModelLoaded && !viewModel.isGeneratingResponse.value) {
                    LOGD("onTrimMemory($level) - unloading model")
                    modelUnloaded = viewModel.unloadModel()
                    isModelLoaded = !modelUnloaded
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatActivityScreenUI(
    viewModel: ChatScreenViewModel,
    onEditChatParamsClick: (Chat, Int) -> Unit,
) {
    val currChat by
    viewModel.currChatState.collectAsStateWithLifecycle(
        lifecycleOwner = LocalLifecycleOwner.current
    )
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    LaunchedEffect(currChat) { viewModel.loadModel() }
    SmolLMAndroidTheme {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                DrawerUI(viewModel, onCloseDrawer = { scope.launch { drawerState.close() } })
                BackHandler(drawerState.isOpen) { scope.launch { drawerState.close() } }
            },
        ) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    TopAppBar(
                        modifier = Modifier.shadow(2.dp),
                        title = {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                AppBarTitleText(
                                    currChat?.name ?: stringResource(R.string.chat_select_chat)
                                )
                                Text(
                                    if (currChat != null && currChat?.llmModelId != -1L) {
                                        viewModel.modelsRepository
                                            .getModelFromId(currChat!!.llmModelId)
                                            ?.name ?: ""
                                    } else {
                                        ""
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(
                                    FeatherIcons.Menu,
                                    contentDescription = stringResource(R.string.chat_view_chats),
                                    tint = MaterialTheme.colorScheme.secondary,
                                )
                            }
                        },
                        actions = {
                            if (currChat != null) {
                                Box {
                                    IconButton(
                                        onClick = {
                                            viewModel.onEvent(
                                                ChatScreenUIEvent.DialogEvents
                                                    .ToggleMoreOptionsPopup(visible = true)
                                            )
                                        }
                                    ) {
                                        Icon(
                                            FeatherIcons.MoreVertical,
                                            contentDescription = "Options",
                                            tint = MaterialTheme.colorScheme.secondary,
                                        )
                                    }
                                    ChatMoreOptionsPopup(
                                        viewModel,
                                        {
                                            onEditChatParamsClick(
                                                currChat!!,
                                                viewModel.modelsRepository
                                                    .getModelFromId(currChat!!.llmModelId)
                                                    .contextSize,
                                            )
                                        },
                                    )
                                }
                            }
                        },
                    )
                },
            ) { innerPadding ->
                Column(
                    modifier =
                        Modifier
                            .padding(innerPadding)
                            .background(MaterialTheme.colorScheme.surface)
                ) {
                    if (currChat != null) {
                        ScreenUI(viewModel, currChat!!)
                    }
                }
            }
            SelectModelsList(viewModel)
            TasksListBottomSheet(viewModel)
            ChangeFolderDialog(viewModel)
            TextFieldDialog()
            ChatMessageOptionsDialog()
        }
    }
}

@Composable
private fun ColumnScope.ScreenUI(viewModel: ChatScreenViewModel, currChat: Chat) {
    val isGeneratingResponse by viewModel.isGeneratingResponse.collectAsStateWithLifecycle()
    RAMUsageLabel(viewModel)
    Spacer(modifier = Modifier.height(4.dp))
    MessagesList(viewModel, isGeneratingResponse, currChat.id)
    MessageInput(viewModel, isGeneratingResponse)
}

@Composable
private fun RAMUsageLabel(viewModel: ChatScreenViewModel) {
    val showRAMUsageLabel by viewModel.showRAMUsageLabel.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var labelText by remember { mutableStateOf("") }
    LaunchedEffect(showRAMUsageLabel) {
        if (showRAMUsageLabel) {
            while (true) {
                val (used, total) = viewModel.getCurrentMemoryUsage()
                labelText = context.getString(R.string.label_device_ram).format(used, total)
                delay(3000L)
            }
        }
    }
    if (showRAMUsageLabel) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            labelText,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }
}
@Composable
private fun ColumnScope.MessagesList(
    viewModel: ChatScreenViewModel,
    isGeneratingResponse: Boolean,
    chatId: Long,
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val messages by viewModel.getChatMessages(chatId).collectAsState(emptyList())
    val lastUserMessageIndex by remember {
        derivedStateOf { messages.indexOfLast { it.isUserMessage } }
    }
    val partialResponse by viewModel.partialResponse.collectAsStateWithLifecycle()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size)
        }
    }
    LazyColumn(state = listState, modifier = Modifier
        .fillMaxSize()
        .weight(1f)) {
        itemsIndexed(messages) { i, chatMessage ->
            MessageListItem(
                viewModel.markwon.render(viewModel.markwon.parse(chatMessage.message)),
                responseGenerationSpeed =
                    if (i == messages.size - 1) viewModel.responseGenerationsSpeed else null,
                responseGenerationTimeSecs =
                    if (i == messages.size - 1) viewModel.responseGenerationTimeSecs else null,
                chatMessage.isUserMessage,
                onCopyClicked = {
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Copied message", chatMessage.message)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(
                            context,
                            context.getString(R.string.chat_message_copied),
                            Toast.LENGTH_SHORT,
                    )
                        .show()
                },
                onShareClicked = {
                    context.startActivity(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, chatMessage.message)
                        }
                    )
                },
                onMessageEdited = { newMessage ->
                    viewModel.deleteMessage(chatMessage.id)
                    if (!messages.last().isUserMessage) {
                        viewModel.deleteMessage(messages.last().id)
                    }
                    viewModel.appDB.addUserMessage(chatId, newMessage)
                    viewModel.unloadModel()
                    viewModel.loadModel(
                        onComplete = {
                            if (it == ModelLoadingState.SUCCESS) {
                                viewModel.sendUserQuery(newMessage, addMessageToDB = false)
                            }
                        }
                    )
                },
                allowEditing = (i == lastUserMessageIndex),
                onDeleteClicked = { viewModel.deleteMessage(chatMessage.id) },
            )
        }
        if (isGeneratingResponse) {
            item {
                MessageListItem(
                    viewModel.markwon.render(viewModel.markwon.parse(partialResponse)),
                    false,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LazyItemScope.MessageListItem(
    message: Spanned,
    isUserMessage: Boolean,
    onCopyClicked: (() -> Unit)? = null,
    onShareClicked: (() -> Unit)? = null,
    onMessageEdited: ((String) -> Unit)? = null,
    onDeleteClicked: (() -> Unit)? = null,
    allowEditing: Boolean = false,
    responseGenerationSpeed: Double? = null,
    responseGenerationTimeSecs: Long? = null,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .animateItemPlacement()
                .padding(vertical = 8.dp, horizontal = 16.dp),
        horizontalArrangement = if (isUserMessage) Arrangement.End else Arrangement.Start,
    ) {
        if (!isUserMessage) {
            Icon(
                FeatherIcons.User,
                contentDescription = null,
                modifier =
                    Modifier.size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(4.dp),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Column(
            modifier =
                Modifier.widthIn(max = 280.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isUserMessage) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.secondaryContainer
                    )
                    .clickable {
                        if (onCopyClicked != null &&
                            onShareClicked != null &&
                            onDeleteClicked != null &&
                            onMessageEdited != null
                        ) {
                            createChatMessageOptionsDialog(
                                ChatMessageOptionsDialog.Data(
                                    onCopyClicked,
                                    onShareClicked,
                                    onMessageEdited,
                                    onDeleteClicked,
                                    allowEditing,
                                )
                            )
                        }
                    }
                    .padding(12.dp)
        ) {
            MarkdownText(message)
            if (responseGenerationSpeed != null && responseGenerationTimeSecs != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "%.2f tok/s · %d secs".format(responseGenerationSpeed, responseGenerationTimeSecs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun MessageInput(viewModel: ChatScreenViewModel, isGeneratingResponse: Boolean) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val questionText = remember { mutableStateOf(viewModel.questionTextDefaultVal) }
    val modelLoadingState by viewModel.modelLoadingState.collectAsStateWithLifecycle()
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .padding(16.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = questionText.value,
            onValueChange = { questionText.value = it },
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    stringResource(
                        if (modelLoadingState == ModelLoadingState.LOADING) {
                            R.string.chat_wait_model_loading
                        } else {
                            R.string.chat_type_message
                        }
                    )
                )
            },
            colors =
                TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                ),
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Send,
                ),
            keyboardActions =
                KeyboardActions(
                    onSend = {
                        if (questionText.value.isNotBlank() && !isGeneratingResponse) {
                            viewModel.sendUserQuery(questionText.value)
                            questionText.value = ""
                            keyboardController?.hide()
                        }
                    }
                ),
            enabled = !isGeneratingResponse && modelLoadingState == ModelLoadingState.SUCCESS,
        )
        IconButton(
            onClick = {
                if (isGeneratingResponse) {
                    viewModel.stopResponseGeneration()
                } else if (questionText.value.isNotBlank()) {
                    viewModel.sendUserQuery(questionText.value)
                    questionText.value = ""
                    keyboardController?.hide()
                }
            },
            enabled = modelLoadingState == ModelLoadingState.SUCCESS,
        ) {
            if (modelLoadingState == ModelLoadingState.LOADING) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else {
                Icon(
                    if (isGeneratingResponse) FeatherIcons.StopCircle else FeatherIcons.Send,
                    contentDescription =
                        stringResource(
                            if (isGeneratingResponse) R.string.chat_stop_response
                            else R.string.chat_send_message
                        ),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DrawerUI(viewModel: ChatScreenViewModel, onCloseDrawer: () -> Unit) {
    val chats by viewModel.getAllChats().collectAsState(emptyList())
    val currChat by viewModel.currChatState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Column(
        modifier =
            Modifier.fillMaxSize()
                .widthIn(max = 300.dp)
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MediumLabelText(stringResource(R.string.chat_all_chats))
            OutlinedButton(
                onClick = {
                    viewModel.onEvent(ChatScreenUIEvent.DialogEvents.ToggleTasksList(true))
                    onCloseDrawer()
                }
            ) {
                Text(stringResource(R.string.chat_tasks))
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        LazyColumn(modifier = Modifier.weight(1f)) {
            itemsIndexed(chats) { _, chat ->
                Text(
                    text = chat.name,
                    modifier =
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (currChat?.id == chat.id)
                                    MaterialTheme.colorScheme.primaryContainer
                                else Color.Transparent
                            )
                            .clickable {
                                viewModel.switchChat(chat)
                                onCloseDrawer()
                            }
                            .padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
        OutlinedButton(
            onClick = {
                val chatCount = viewModel.appDB.getChatsCount()
                val newChat = viewModel.appDB.addChat(chatName = "Untitled ${chatCount + 1}")
                viewModel.switchChat(newChat)
                onCloseDrawer()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.chat_new_chat))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TasksListBottomSheet(viewModel: ChatScreenViewModel) {
    val showTasksListSheet by
        viewModel.showTasksListSheet.collectAsStateWithLifecycle(initialValue = false)
    val context = LocalContext.current
    if (showTasksListSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                viewModel.onEvent(ChatScreenUIEvent.DialogEvents.ToggleTasksList(false))
            }
        ) {
            TasksList(
                viewModel.appDB,
                onTaskClick = { task -> createChatFromTask(viewModel, task) },
                onManageTasksClick = {
                    context.startActivity(Intent(context, ManageTasksActivity::class.java))
                },
            )
        }
    }
}

@Composable
private fun ChangeFolderDialog(viewModel: ChatScreenViewModel) {
    val currChat by viewModel.currChatState.collectAsStateWithLifecycle()
    val showChangeFolderDialog by
        viewModel.showChangeFolderDialog.collectAsStateWithLifecycle(initialValue = false)
    if (showChangeFolderDialog && currChat != null) {
        ChangeFolderDialogUI(viewModel, currChat!!)
    }
}

@Composable
private fun MarkdownText(spanned: Spanned) {
    androidx.compose.foundation.text.BasicText(
        text = androidx.compose.ui.text.AnnotatedString(spanned.toString()),
        style =
            MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSecondaryContainer
            ),
    )
}

private fun createChatFromTask(viewModel: ChatScreenViewModel, task: Task) {
    val chatCount = viewModel.appDB.getChatsCount()
    val newChat =
        viewModel.appDB.addChat(
            chatName = task.name + " ${chatCount + 1}",
            systemPrompt = task.systemPrompt,
            userPrompt = task.userPrompt,
        )
    viewModel.switchChat(newChat)
    viewModel.onEvent(ChatScreenUIEvent.DialogEvents.ToggleTasksList(false))
}
