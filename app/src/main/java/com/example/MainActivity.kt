package com.example

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainAppScreen()
            }
        }
    }
}

class MainViewModel(context: Context) : ViewModel() {
    private val api = OllamaApi()
    private val executor = OllamaExecutor(context)

    // Configuration Inputs
    var hostUrlState by mutableStateOf("127.0.0.1:11434")
    var originsState by mutableStateOf("*")
    var downloadUrlState by mutableStateOf("https://github.com/sunshine0523/OllamaServer/raw/master/android/app/src/main/assets/arm64-v8a/ollama")

    // Daemon states
    var binaryInstalled by mutableStateOf(false)
    var serviceActive by mutableStateOf(false)
    var setupProgress by mutableStateOf(0)
    var setupStatus by mutableStateOf("")
    var isInstalling by mutableStateOf(false)

    // Api Status
    var apiOnline by mutableStateOf(false)
    var apiHostMessage by mutableStateOf("Disconnected")

    // Model Library states
    var modelList by mutableStateOf<List<OllamaModel>>(emptyList())
    var isLoadingModels by mutableStateOf(false)
    var customModelPullName by mutableStateOf("qwen2:0.5b")
    var pullProgressStatus by mutableStateOf("")
    var pullProgressPercent by mutableStateOf(-1)
    var isPullingActive by mutableStateOf(false)

    // Chat Playground states
    var selectedModelChat by mutableStateOf("")
    var chatMessageInput by mutableStateOf("")
    val chatHistory = mutableStateListOf<ChatMessage>()
    var isGeneratingResponse by mutableStateOf(false)

    // Log terminal list
    val liveLogs = mutableStateListOf<String>()

    init {
        checkBinaryInstalled()
        refreshServiceStatus()

        // Sync initial logs if service was already running
        synchronized(OllamaService.logBuffer) {
            liveLogs.addAll(OllamaService.logBuffer)
        }

        // Register callback for active background service logs
        OllamaService.onLogReceived = { line ->
            viewModelScope.launch(Dispatchers.Main) {
                liveLogs.add(line)
                if (liveLogs.size > 1000) {
                    liveLogs.removeAt(0)
                }
            }
        }

        // Periodic API Connection Watcher
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val base = "http://$hostUrlState"
                api.checkRunning(base) { online, message ->
                    apiOnline = online
                    apiHostMessage = if (online) "Online" else "Offline ($message)"
                }
                if (apiOnline) {
                    // Update active model lists
                    api.listModels(base) { list, _ ->
                        if (list != null) {
                            modelList = list
                            if (selectedModelChat.isEmpty() && list.isNotEmpty()) {
                                selectedModelChat = list.first().name
                            }
                        }
                    }
                }
                delay(3000)
            }
        }
    }

    fun checkBinaryInstalled() {
        binaryInstalled = executor.ollamaFile.exists() && executor.ollamaFile.canExecute()
    }

    fun refreshServiceStatus() {
        serviceActive = OllamaService.isRunning
        checkBinaryInstalled()
    }

    fun triggerBinaryInstall(context: Context) {
        if (isInstalling) return
        isInstalling = true
        setupProgress = 0
        setupStatus = "Initiating installer..."

        executor.downloadBinary(
            downloadUrlState,
            onProgress = { percent, msg ->
                viewModelScope.launch(Dispatchers.Main) {
                    setupProgress = percent
                    setupStatus = msg
                }
            },
            onComplete = { success, msg ->
                viewModelScope.launch(Dispatchers.Main) {
                    isInstalling = false
                    setupStatus = msg
                    checkBinaryInstalled()
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    fun toggleOllamaService(context: Context) {
        if (OllamaService.isRunning) {
            val stopIntent = Intent(context, OllamaService::class.java)
            context.stopService(stopIntent)
            viewModelScope.launch(Dispatchers.Main) {
                delay(500)
                refreshServiceStatus()
            }
        } else {
            val startIntent = Intent(context, OllamaService::class.java).apply {
                putExtra("host", hostUrlState)
                putExtra("origins", originsState)
            }
            context.startForegroundService(startIntent)
            viewModelScope.launch(Dispatchers.Main) {
                delay(500)
                refreshServiceStatus()
            }
        }
    }

    fun pullModel(context: Context) {
        val model = customModelPullName.trim()
        if (model.isEmpty()) {
            Toast.makeText(context, "Please enter a valid model name", Toast.LENGTH_SHORT).show()
            return
        }
        if (!apiOnline) {
            Toast.makeText(context, "Local server must be running to pull models", Toast.LENGTH_SHORT).show()
            return
        }

        isPullingActive = true
        pullProgressStatus = "Starting pull request..."
        pullProgressPercent = -1

        val base = "http://$hostUrlState"
        api.pullModelStream(
            base,
            model,
            onProgress = { status, pct ->
                viewModelScope.launch(Dispatchers.Main) {
                    pullProgressStatus = status
                    pullProgressPercent = pct
                }
            },
            onComplete = { success, msg ->
                viewModelScope.launch(Dispatchers.Main) {
                    isPullingActive = false
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    // Force refresh list
                    api.listModels(base) { list, _ ->
                        if (list != null) modelList = list
                    }
                }
            }
        )
    }

    fun deleteModel(context: Context, modelName: String) {
        if (!apiOnline) return
        val base = "http://$hostUrlState"
        api.deleteModel(base, modelName) { success, msg ->
            viewModelScope.launch(Dispatchers.Main) {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                api.listModels(base) { list, _ ->
                    if (list != null) modelList = list
                }
            }
        }
    }

    fun startChatSession() {
        chatHistory.clear()
        chatHistory.add(ChatMessage("assistant", "System initialized. Selected model: $selectedModelChat. What would you like to request?"))
    }

    fun triggerChatInference(context: Context) {
        val text = chatMessageInput.trim()
        if (text.isEmpty() || selectedModelChat.isEmpty()) return
        if (!apiOnline) {
            Toast.makeText(context, "Local Ollama server is offline", Toast.LENGTH_SHORT).show()
            return
        }

        chatHistory.add(ChatMessage("user", text))
        chatMessageInput = ""
        isGeneratingResponse = true

        val assistantResponseIndex = chatHistory.size
        chatHistory.add(ChatMessage("assistant", "Thinking..."))

        val base = "http://$hostUrlState"
        var assistantResponseText = ""

        api.chatStream(
            base,
            selectedModelChat,
            chatHistory.subList(0, assistantResponseIndex).toList(),
            onTokenGenerated = { token ->
                viewModelScope.launch(Dispatchers.Main) {
                    assistantResponseText += token
                    chatHistory[assistantResponseIndex] = ChatMessage("assistant", assistantResponseText)
                }
            },
            onComplete = { success, msg ->
                viewModelScope.launch(Dispatchers.Main) {
                    isGeneratingResponse = false
                    if (!success) {
                        chatHistory[assistantResponseIndex] = ChatMessage("assistant", "Transmission error: $msg")
                    }
                }
            }
        )
    }

    fun clearLogTerminal() {
        synchronized(OllamaService.logBuffer) {
            OllamaService.logBuffer.clear()
        }
        liveLogs.clear()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen() {
    val context = LocalContext.current
    val vm: MainViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(context.applicationContext) as T
        }
    })

    var activeTab by remember { mutableStateOf(0) }
    val tabTitles = listOf("Operations", "Library", "Playground", "System Logs")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "App logo",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = "Ollama Server Client",
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                },
                actions = {
                    Row(
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .background(
                                if (vm.apiOnline) Color(0xFF1B5E20) else Color(0xFFB71C1C),
                                RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (vm.apiOnline) Color.Green else Color.Red)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (vm.apiOnline) "Local Daemon Online" else "Daemon Offline",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Dashboard Status Banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.surfaceVariant,
                                MaterialTheme.colorScheme.surface
                            )
                        )
                    )
                    .padding(16.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "SERVER BINARY",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                if (vm.binaryInstalled) "Arm64 Native Binary Ready" else "No Executable Available",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (vm.binaryInstalled) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Ready",
                                tint = Color.Green,
                                modifier = Modifier.size(28.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Missing binary",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "DAEMON PROCESS",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                if (vm.serviceActive) "Service Daemon Running (Port :11434)" else "Stopped",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Switch(
                            checked = vm.serviceActive,
                            onCheckedChange = { vm.toggleOllamaService(context) },
                            enabled = vm.binaryInstalled,
                            modifier = Modifier.testTag("daemon_toggle")
                        )
                    }
                }
            }

            // Tab bar switcher
            TabRow(selectedTabIndex = activeTab) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = activeTab == index,
                        onClick = { activeTab = index },
                        text = {
                            Text(
                                text = title,
                                fontSize = 12.sp,
                                maxLines = 1,
                                fontWeight = if (activeTab == index) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when (activeTab) {
                    0 -> OperationsTab(vm, context)
                    1 -> ModelLibraryTab(vm, context)
                    2 -> ChatPlaygroundTab(vm, context)
                    3 -> SystemLogsTab(vm)
                }
            }
        }
    }
}

@Composable
fun OperationsTab(vm: MainViewModel, context: Context) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Binary installation card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Ollama Daemon Engine",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Specify a direct URL to fetch the compiled Ollama executables to your Android device local folder.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = vm.downloadUrlState,
                    onValueChange = { vm.downloadUrlState = it },
                    label = { Text("Executable Download URL") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("download_url_input"),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (vm.binaryInstalled) "Installed" else "Missing binary asset",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (vm.binaryInstalled) Color.Green else MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = if (vm.isInstalling) vm.setupStatus else "Local ARM64 installation matches OS",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Button(
                        onClick = { vm.triggerBinaryInstall(context) },
                        enabled = !vm.isInstalling,
                        modifier = Modifier.testTag("binary_download_btn")
                    ) {
                        if (vm.isInstalling) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text(if (vm.binaryInstalled) "Reinstall / Setup" else "Setup / Download")
                        }
                    }
                }

                if (vm.isInstalling && vm.setupProgress >= 0) {
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { vm.setupProgress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Port bindings and Host options
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Config Environment Variables",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = vm.hostUrlState,
                    onValueChange = { vm.hostUrlState = it },
                    label = { Text("OLLAMA_HOST (Listener IP:Port)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("host_input"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next)
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = vm.originsState,
                    onValueChange = { vm.originsState = it },
                    label = { Text("OLLAMA_ORIGINS (CORS Rules)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("origins_input"),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Defaults to 127.0.0.1:11434 interface. Set to '0.0.0.0:11434' to reveal your mobile server to other platforms on the same Wi-Fi network.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ModelLibraryTab(vm: MainViewModel, context: Context) {
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Pull model card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Pull Models from Official Registry",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = vm.customModelPullName,
                        onValueChange = { vm.customModelPullName = it },
                        label = { Text("Registry Model (e.g., qwen2:0.5b)") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("model_pull_input"),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            vm.pullModel(context)
                        },
                        enabled = !vm.isPullingActive && vm.apiOnline,
                        modifier = Modifier
                            .height(56.dp)
                            .padding(top = 4.dp)
                            .testTag("pull_start_btn")
                    ) {
                        Text("Pull")
                    }
                }

                if (vm.isPullingActive) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = vm.pullProgressStatus,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    if (vm.pullProgressPercent >= 0) {
                        LinearProgressIndicator(
                            progress = { vm.pullProgressPercent / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        // Popular Model presets quick launcher
        Text(
            text = "Popular Lightweight LLMs",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("qwen2:0.5b", "gemma:2b", "tinyllama").forEach { preset ->
                OutlinedCard(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { vm.customModelPullName = preset },
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = preset,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        Divider()

        Text(
            text = "Installed Model Inventory",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        if (!vm.apiOnline) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Local Ollama server is offline.\nStart the service to load model list.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (vm.modelList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No models installed yet.\nPull a model above to begin local execution.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(vm.modelList) { model ->
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = model.name,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                                Text(
                                    text = "Size: ${String.format("%.2f", model.size / 1024.0 / 1024.0 / 1024.0)} GB",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Row {
                                FilledTonalButton(
                                    onClick = {
                                        vm.selectedModelChat = model.name
                                        Toast.makeText(context, "Selected ${model.name} for chat", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.padding(end = 4.dp)
                                ) {
                                    Text("Select")
                                }

                                IconButton(
                                    onClick = { vm.deleteModel(context, model.name) },
                                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete Model"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatPlaygroundTab(vm: MainViewModel, context: Context) {
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current

    LaunchedEffect(vm.chatHistory.size) {
        if (vm.chatHistory.isNotEmpty()) {
            listState.animateScrollToItem(vm.chatHistory.size - 1)
        }
    }

    LaunchedEffect(vm.selectedModelChat) {
        if (vm.selectedModelChat.isNotEmpty() && vm.chatHistory.isEmpty()) {
            vm.startChatSession()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Model Selection Dropdown indicator
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Face,
                        contentDescription = "Selected model icon",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = "Active LLM: ${vm.selectedModelChat.ifEmpty { "None Selected" }}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                if (vm.modelList.isNotEmpty()) {
                    TextButton(onClick = { vm.startChatSession() }) {
                        Text("Reset Chat")
                    }
                }
            }
        }

        if (vm.selectedModelChat.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Please pull and select a model\nfrom the Library tab first.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 12.dp)
            ) {
                items(vm.chatHistory) { msg ->
                    val isUser = msg.role == "user"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(
                                    RoundedCornerShape(
                                        topStart = 16.dp,
                                        topEnd = 16.dp,
                                        bottomStart = if (isUser) 16.dp else 0.dp,
                                        bottomEnd = if (isUser) 0.dp else 16.dp
                                    )
                                )
                                .background(
                                    if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer
                                )
                                .padding(12.dp)
                                .widthIn(max = 280.dp)
                        ) {
                            Text(
                                text = msg.content,
                                color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }

            // Input Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = vm.chatMessageInput,
                    onValueChange = { vm.chatMessageInput = it },
                    placeholder = { Text("Ask local AI model...") },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chat_input"),
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        focusManager.clearFocus()
                        vm.triggerChatInference(context)
                    })
                )

                Spacer(modifier = Modifier.width(8.dp))

                FloatingActionButton(
                    onClick = {
                        focusManager.clearFocus()
                        vm.triggerChatInference(context)
                    },
                    modifier = Modifier.size(56.dp).testTag("chat_send_btn"),
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    if (vm.isGeneratingResponse) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Send Message",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SystemLogsTab(vm: MainViewModel) {
    val listState = rememberLazyListState()

    LaunchedEffect(vm.liveLogs.size) {
        if (vm.liveLogs.isNotEmpty()) {
            listState.animateScrollToItem(vm.liveLogs.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Live Terminal Console",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            TextButton(
                onClick = { vm.clearLogTerminal() },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Clear logs")
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black)
                .padding(8.dp)
        ) {
            if (vm.liveLogs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Stdout log pipeline quiet.\nBackground service is stopped or inactive.",
                        color = Color.DarkGray,
                        textAlign = TextAlign.Center,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(vm.liveLogs) { logLine ->
                        Text(
                            text = logLine,
                            color = Color.Green,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}
