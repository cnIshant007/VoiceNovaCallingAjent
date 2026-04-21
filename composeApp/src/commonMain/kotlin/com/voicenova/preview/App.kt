package com.voicenova.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock

private enum class PreviewSection(val label: String, val shortLabel: String) {
    Dashboard("Dashboard", "Home"),
    Calling("Calling", "Call"),
    History("Call History", "Logs"),
    AgentTest("Agent Test", "Test"),
    Website("Website", "Site"),
    Simulators("Device Paths", "Paths"),
    Analytics("Analytics", "Stats"),
    Settings("Settings", "Prefs")
}

private data class AgentTestState(
    val sessionId: String? = null,
    val transcript: List<String> = emptyList(),
    val detectedLanguage: String = "hi-IN"
)

private data class OutboundCallState(
    val phoneNumber: String = "",
    val statusMessage: String? = null,
    val callSid: String? = null,
    val liveStatus: String = "idle",
    val startedAtMillis: Long? = null
)

@Composable
fun VoiceNovaPreviewApp() {
    VoiceNovaTheme {
        val api = remember { BackendApi.create(createPreviewHttpClient()) }
        val scope = rememberCoroutineScope()

        var selectedSection by remember { mutableStateOf(PreviewSection.Dashboard) }
        var backendBaseUrl by remember { mutableStateOf(defaultBackendBaseUrl()) }
        var backendDraft by remember(backendBaseUrl) { mutableStateOf(backendBaseUrl) }

        var health by remember { mutableStateOf<HealthResponse?>(null) }
        var contextSummary by remember { mutableStateOf<ContextSummaryResponse?>(null) }
        var backendStatus by remember { mutableStateOf<BackendStatusResponse?>(null) }
        var analytics by remember { mutableStateOf<AnalyticsResponse?>(null) }
        var callHistory by remember { mutableStateOf<List<CallHistoryItem>>(emptyList()) }
        var apiError by remember { mutableStateOf<String?>(null) }
        var loadingOverview by remember { mutableStateOf(false) }
        var lastUpdatedLabel by remember { mutableStateOf("Waiting for first sync") }

        var chatInput by remember { mutableStateOf("How do I reset my password?") }
        var selectedLanguage by remember { mutableStateOf("hi-IN") }
        var agentState by remember { mutableStateOf(AgentTestState()) }
        var testingAgent by remember { mutableStateOf(false) }
        var outboundCall by remember { mutableStateOf(OutboundCallState()) }
        var callingNow by remember { mutableStateOf(false) }

        val previewUrl = remember(backendBaseUrl) { siteUrlFor(backendBaseUrl) }
        val connected = health?.status.equals("ok", ignoreCase = true) && backendStatus != null && apiError == null

        fun applyDefaultBackend() {
            val defaultUrl = defaultBackendBaseUrl()
            backendDraft = defaultUrl
            backendBaseUrl = defaultUrl
        }

        fun loadOverview() {
            scope.launch {
                loadingOverview = true
                apiError = null
                val healthResult = runCatching { api.fetchHealth(backendBaseUrl) }
                val contextResult = runCatching { api.fetchContextSummary(backendBaseUrl) }
                val statusResult = runCatching { api.fetchBackendStatus(backendBaseUrl) }
                val analyticsResult = runCatching { api.fetchAnalytics(backendBaseUrl) }
                val historyResult = runCatching { api.fetchCallHistory(backendBaseUrl) }

                healthResult.onSuccess { health = it }
                    .onFailure {
                        health = null
                        apiError = userFacingError(it)
                    }

                contextResult.onSuccess { contextSummary = it }
                    .onFailure {
                        contextSummary = null
                        apiError = apiError ?: userFacingError(it)
                    }

                statusResult.onSuccess { backendStatus = it }
                    .onFailure {
                        backendStatus = null
                        apiError = apiError ?: userFacingError(it)
                    }

                analyticsResult.onSuccess { analytics = it }
                    .onFailure {
                        analytics = null
                        apiError = apiError ?: userFacingError(it)
                    }

                historyResult.onSuccess { callHistory = it }
                    .onFailure {
                        callHistory = emptyList()
                        apiError = apiError ?: userFacingError(it)
                    }

                lastUpdatedLabel = if (apiError == null) "Synced just now" else "Last sync failed"
                loadingOverview = false
            }
        }

        LaunchedEffect(backendBaseUrl) {
            loadOverview()
            agentState = AgentTestState()
            outboundCall = outboundCall.copy(statusMessage = null, callSid = null, liveStatus = "idle")
        }

        LaunchedEffect(outboundCall.callSid, backendBaseUrl) {
            val callSid = outboundCall.callSid ?: return@LaunchedEffect
            while (true) {
                runCatching { api.fetchCallStatus(backendBaseUrl, callSid) }
                    .onSuccess { response ->
                        outboundCall = outboundCall.copy(
                            liveStatus = response.status,
                            statusMessage = "Call ${response.status.replaceFirstChar { it.uppercase() }}"
                        )
                        if (!response.active) {
                            if (response.status == "completed") {
                                outboundCall = outboundCall.copy(callSid = null)
                            }
                            return@LaunchedEffect
                        }
                    }
                    .onFailure {
                        apiError = apiError ?: userFacingError(it)
                        return@LaunchedEffect
                    }
                delay(3000)
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(AppBackground)
        ) {
            val compactLayout = maxWidth < 960.dp

            if (compactLayout) {
                Column(modifier = Modifier.padding(top = 80.dp).fillMaxSize()) {
                    TopBar(connected = connected, selectedSection = selectedSection, backendBaseUrl = backendBaseUrl)
                    MobileNavigation(
                        selectedSection = selectedSection,
                        onSelected = { selectedSection = it }
                    )
                        MainContent(
                        selectedSection = selectedSection,
                        onSelectedSectionChange = { selectedSection = it },
                        compactLayout = true,
                        connected = connected,
                        backendBaseUrl = backendBaseUrl,
                        backendDraft = backendDraft,
                        previewUrl = previewUrl,
                        health = health,
                        contextSummary = contextSummary,
                        backendStatus = backendStatus,
                        analytics = analytics,
                        callHistory = callHistory,
                        apiError = apiError,
                        loadingOverview = loadingOverview,
                        lastUpdatedLabel = lastUpdatedLabel,
                        agentState = agentState,
                        outboundCall = outboundCall,
                        chatInput = chatInput,
                        selectedLanguage = selectedLanguage,
                        onChatInputChange = { chatInput = it },
                        onLanguageSelected = { selectedLanguage = it },
                        onPhoneNumberChange = { outboundCall = outboundCall.copy(phoneNumber = it) },
                        onDigitPressed = { digit -> outboundCall = outboundCall.copy(phoneNumber = outboundCall.phoneNumber + digit) },
                        onDeleteDigit = { outboundCall = outboundCall.copy(phoneNumber = outboundCall.phoneNumber.dropLast(1)) },
                        onBackendDraftChange = { backendDraft = it },
                        onApplyBackend = { backendBaseUrl = backendDraft.trim() },
                        onUseDefaultBackend = { applyDefaultBackend() },
                        testingAgent = testingAgent,
                        callingNow = callingNow,
                        onStartAgentTest = {
                            scope.launch {
                                testingAgent = true
                                apiError = null
                                runCatching { api.startConversation(backendBaseUrl, selectedLanguage) }
                                    .onSuccess { response ->
                                        agentState = AgentTestState(
                                            sessionId = response.sessionId,
                                            transcript = listOf("Agent: ${response.agentGreeting}"),
                                            detectedLanguage = response.detectedLanguage
                                        )
                                    }
                                    .onFailure { apiError = userFacingError(it) }
                                testingAgent = false
                            }
                        },
                        onSendAgentMessage = {
                            val sessionId = agentState.sessionId ?: return@MainContent
                            scope.launch {
                                testingAgent = true
                                apiError = null
                                runCatching { api.sendMessage(backendBaseUrl, sessionId, chatInput) }
                                    .onSuccess { response ->
                                        agentState = agentState.copy(
                                            transcript = agentState.transcript + listOf(
                                                "You: $chatInput",
                                                "Agent: ${response.agentReply}"
                                            ),
                                            detectedLanguage = response.detectedLanguage
                                        )
                                        chatInput = ""
                                    }
                                    .onFailure { apiError = userFacingError(it) }
                                testingAgent = false
                            }
                        },
                        onStartOutboundCall = {
                            scope.launch {
                                callingNow = true
                                apiError = null
                                runCatching { api.startOutboundCall(backendBaseUrl, outboundCall.phoneNumber.trim(), selectedLanguage) }
                                    .onSuccess { response ->
                                        outboundCall = outboundCall.copy(
                                            statusMessage = "Call ${response.status} from ${response.from} to ${response.to}",
                                            callSid = response.callSid,
                                            liveStatus = response.status,
                                            startedAtMillis = Clock.System.now().epochSeconds
                                        )
                                    }
                                    .onFailure { apiError = userFacingError(it) }
                                callingNow = false
                            }
                        },
                        onEndOutboundCall = {
                            val sid = outboundCall.callSid ?: return@MainContent
                            scope.launch {
                                callingNow = true
                                apiError = null
                                runCatching { api.endOutboundCall(backendBaseUrl, sid) }
                                    .onSuccess { response ->
                                        outboundCall = outboundCall.copy(
                                            statusMessage = "Call ${response.status}",
                                            liveStatus = response.status,
                                            callSid = if (response.ended) null else sid,
                                            startedAtMillis = if (response.ended) null else outboundCall.startedAtMillis
                                        )
                                    }
                                    .onFailure { apiError = userFacingError(it) }
                                callingNow = false
                                loadOverview()
                            }
                        },
                        onStartDemoCall = {
                            scope.launch {
                                testingAgent = true
                                apiError = null
                                runCatching { api.startConversation(backendBaseUrl, selectedLanguage) }
                                    .onSuccess { response ->
                                        selectedSection = PreviewSection.AgentTest
                                        agentState = AgentTestState(
                                            sessionId = response.sessionId,
                                            transcript = listOf("Agent: ${response.agentGreeting}"),
                                            detectedLanguage = response.detectedLanguage
                                        )
                                    }
                                    .onFailure { apiError = userFacingError(it) }
                                testingAgent = false
                            }
                        },
                        onRefresh = { loadOverview() },
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                Row(modifier = Modifier.fillMaxSize()) {
                    Sidebar(
                        selectedSection = selectedSection,
                        connected = connected,
                        onSelected = { selectedSection = it },
                        modifier = Modifier.width(280.dp)
                    )
                    MainContent(
                        selectedSection = selectedSection,
                        onSelectedSectionChange = { selectedSection = it },
                        compactLayout = false,
                        connected = connected,
                        backendBaseUrl = backendBaseUrl,
                        backendDraft = backendDraft,
                        previewUrl = previewUrl,
                        health = health,
                        contextSummary = contextSummary,
                        backendStatus = backendStatus,
                        analytics = analytics,
                        callHistory = callHistory,
                        apiError = apiError,
                        loadingOverview = loadingOverview,
                        lastUpdatedLabel = lastUpdatedLabel,
                        agentState = agentState,
                        outboundCall = outboundCall,
                        chatInput = chatInput,
                        selectedLanguage = selectedLanguage,
                        onChatInputChange = { chatInput = it },
                        onLanguageSelected = { selectedLanguage = it },
                        onPhoneNumberChange = { outboundCall = outboundCall.copy(phoneNumber = it) },
                        onDigitPressed = { digit -> outboundCall = outboundCall.copy(phoneNumber = outboundCall.phoneNumber + digit) },
                        onDeleteDigit = { outboundCall = outboundCall.copy(phoneNumber = outboundCall.phoneNumber.dropLast(1)) },
                        onBackendDraftChange = { backendDraft = it },
                        onApplyBackend = { backendBaseUrl = backendDraft.trim() },
                        onUseDefaultBackend = { applyDefaultBackend() },
                        testingAgent = testingAgent,
                        callingNow = callingNow,
                        onStartAgentTest = {
                            scope.launch {
                                testingAgent = true
                                apiError = null
                                runCatching { api.startConversation(backendBaseUrl, selectedLanguage) }
                                    .onSuccess { response ->
                                        agentState = AgentTestState(
                                            sessionId = response.sessionId,
                                            transcript = listOf("Agent: ${response.agentGreeting}"),
                                            detectedLanguage = response.detectedLanguage
                                        )
                                    }
                                    .onFailure { apiError = userFacingError(it) }
                                testingAgent = false
                            }
                        },
                        onSendAgentMessage = {
                            val sessionId = agentState.sessionId ?: return@MainContent
                            scope.launch {
                                testingAgent = true
                                apiError = null
                                runCatching { api.sendMessage(backendBaseUrl, sessionId, chatInput) }
                                    .onSuccess { response ->
                                        agentState = agentState.copy(
                                            transcript = agentState.transcript + listOf(
                                                "You: $chatInput",
                                                "Agent: ${response.agentReply}"
                                            ),
                                            detectedLanguage = response.detectedLanguage
                                        )
                                        chatInput = ""
                                    }
                                    .onFailure { apiError = userFacingError(it) }
                                testingAgent = false
                            }
                        },
                        onStartOutboundCall = {
                            scope.launch {
                                callingNow = true
                                apiError = null
                                runCatching { api.startOutboundCall(backendBaseUrl, outboundCall.phoneNumber.trim(), selectedLanguage) }
                                    .onSuccess { response ->
                                        outboundCall = outboundCall.copy(
                                            statusMessage = "Call ${response.status} from ${response.from} to ${response.to}",
                                            callSid = response.callSid,
                                            liveStatus = response.status,
                                            startedAtMillis = Clock.System.now().epochSeconds
                                        )
                                    }
                                    .onFailure { apiError = userFacingError(it) }
                                callingNow = false
                            }
                        },
                        onEndOutboundCall = {
                            val sid = outboundCall.callSid ?: return@MainContent
                            scope.launch {
                                callingNow = true
                                apiError = null
                                runCatching { api.endOutboundCall(backendBaseUrl, sid) }
                                    .onSuccess { response ->
                                        outboundCall = outboundCall.copy(
                                            statusMessage = "Call ${response.status}",
                                            liveStatus = response.status,
                                            callSid = if (response.ended) null else sid,
                                            startedAtMillis = if (response.ended) null else outboundCall.startedAtMillis
                                        )
                                    }
                                    .onFailure { apiError = userFacingError(it) }
                                callingNow = false
                                loadOverview()
                            }
                        },
                        onStartDemoCall = {
                            scope.launch {
                                testingAgent = true
                                apiError = null
                                runCatching { api.startConversation(backendBaseUrl, selectedLanguage) }
                                    .onSuccess { response ->
                                        selectedSection = PreviewSection.AgentTest
                                        agentState = AgentTestState(
                                            sessionId = response.sessionId,
                                            transcript = listOf("Agent: ${response.agentGreeting}"),
                                            detectedLanguage = response.detectedLanguage
                                        )
                                    }
                                    .onFailure { apiError = userFacingError(it) }
                                testingAgent = false
                            }
                        },
                        onRefresh = { loadOverview() },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (outboundCall.callSid != null) {
            Dialog(
                onDismissRequest = { },
                properties = DialogProperties(
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false,
                    usePlatformDefaultWidth = false
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF05070F))
                        .padding(18.dp)
                ) {
                    ActiveCallOverlay(
                        outboundCall = outboundCall,
                        selectedLanguage = selectedLanguage,
                        telephonyMode = if (backendStatus?.twilioMockMode == true) "Mock" else "Live",
                        backendStatus = backendStatus,
                        onConnectExpert = { expert ->
                            val sid = outboundCall.callSid ?: return@ActiveCallOverlay "Missing call SID."
                            val response = api.connectExpert(backendBaseUrl, sid, expert)
                            "Expert is connecting (conference ${response.conferenceName})."
                        },
                        onConnectMonitor = { monitor ->
                            val sid = outboundCall.callSid ?: return@ActiveCallOverlay "Missing call SID."
                            val response = api.connectMonitor(backendBaseUrl, sid, monitor)
                            "Listen-only line is connecting (conference ${response.conferenceName})."
                        },
                        onEndOutboundCall = {
                            scope.launch {
                                callingNow = true
                                apiError = null
                                val sid = outboundCall.callSid ?: return@launch
                                runCatching { api.endOutboundCall(backendBaseUrl, sid) }
                                    .onSuccess { response ->
                                        outboundCall = outboundCall.copy(
                                            statusMessage = "Call ${response.status}",
                                            liveStatus = response.status,
                                            callSid = if (response.ended) null else sid,
                                            startedAtMillis = if (response.ended) null else outboundCall.startedAtMillis
                                        )
                                    }
                                    .onFailure { apiError = userFacingError(it) }
                                callingNow = false
                                loadOverview()
                            }
                        },
                        callingNow = callingNow
                    )
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    connected: Boolean,
    selectedSection: PreviewSection,
    backendBaseUrl: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SidebarBackground)
            .border(1.dp, DividerColor)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .background(
                    brush = Brush.linearGradient(listOf(PrimaryBlue, AccentBlue)),
                    shape = RoundedCornerShape(14.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text("V", color = Color.White, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("VoiceNova Control", color = PrimaryText, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(selectedSection.label, color = SecondaryText, fontSize = 12.sp)
        }
        StatusChip(
            label = if (connected) "Connected" else "Offline",
            connected = connected
        )
        Spacer(Modifier.width(10.dp))
    }
}

@Composable
private fun Sidebar(
    selectedSection: PreviewSection,
    connected: Boolean,
    onSelected: (PreviewSection) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SidebarBackground)
            .border(1.dp, DividerColor)
            .padding(20.dp)
    ) {
        Text("VoiceNova", color = PrimaryText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Mobile command center", color = SecondaryText, fontSize = 13.sp)
        Spacer(Modifier.height(18.dp))
        StatusChip(label = if (connected) "Backend connected" else "Backend disconnected", connected = connected)
        Spacer(Modifier.height(24.dp))
        PreviewSection.entries.forEach { section ->
            NavigationItem(
                label = section.label,
                shortLabel = section.shortLabel,
                selected = section == selectedSection,
                onClick = { onSelected(section) }
            )
        }
    }
}

@Composable
private fun MobileNavigation(
    selectedSection: PreviewSection,
    onSelected: (PreviewSection) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .background(SidebarBackground)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        PreviewSection.entries.forEach { section ->
            val selected = section == selectedSection
            Row(
                modifier = Modifier
                    .background(if (selected) SelectedSurface else CardSurface, RoundedCornerShape(999.dp))
                    .border(1.dp, if (selected) PrimaryBlue.copy(alpha = 0.35f) else DividerColor, RoundedCornerShape(999.dp))
                    .clickable { onSelected(section) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(section.shortLabel, color = if (selected) PrimaryBlue else SecondaryText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun NavigationItem(
    label: String,
    shortLabel: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(if (selected) SelectedSurface else Color.Transparent, RoundedCornerShape(16.dp))
            .border(1.dp, if (selected) PrimaryBlue.copy(alpha = 0.25f) else Color.Transparent, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(if (selected) PrimaryBlue.copy(alpha = 0.14f) else CardSurface, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(shortLabel.take(1), color = if (selected) PrimaryBlue else SecondaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(12.dp))
        Text(label, color = if (selected) PrimaryText else SecondaryText, fontSize = 14.sp)
    }
}

@Composable
private fun MainContent(
    selectedSection: PreviewSection,
    onSelectedSectionChange: (PreviewSection) -> Unit,
    compactLayout: Boolean,
    connected: Boolean,
    backendBaseUrl: String,
    backendDraft: String,
    previewUrl: String,
    health: HealthResponse?,
    contextSummary: ContextSummaryResponse?,
    backendStatus: BackendStatusResponse?,
    analytics: AnalyticsResponse?,
    callHistory: List<CallHistoryItem>,
    apiError: String?,
    loadingOverview: Boolean,
    lastUpdatedLabel: String,
    agentState: AgentTestState,
    outboundCall: OutboundCallState,
    chatInput: String,
    selectedLanguage: String,
    onChatInputChange: (String) -> Unit,
    onLanguageSelected: (String) -> Unit,
    onPhoneNumberChange: (String) -> Unit,
    onDigitPressed: (String) -> Unit,
    onDeleteDigit: () -> Unit,
    onBackendDraftChange: (String) -> Unit,
    onApplyBackend: () -> Unit,
    onUseDefaultBackend: () -> Unit,
    testingAgent: Boolean,
    callingNow: Boolean,
    onStartAgentTest: () -> Unit,
    onSendAgentMessage: () -> Unit,
    onStartOutboundCall: () -> Unit,
    onEndOutboundCall: () -> Unit,
    onStartDemoCall: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(if (compactLayout) 16.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        SectionHero(
            title = selectedSection.label,
            description = when (selectedSection) {
                PreviewSection.Dashboard -> "Live connection, agent controls, and real call actions"
                PreviewSection.Calling -> "Dedicated live calling screen for real Twilio-triggered calls"
                PreviewSection.History -> "Full chronological record of all incoming and outgoing calls"
                PreviewSection.AgentTest -> "Dedicated agent testing screen for backend conversation checks"
                PreviewSection.Website -> "Full website preview on a dedicated page with share support"
                PreviewSection.Simulators -> "Current device routing and backend access paths"
                PreviewSection.Analytics -> "Knowledge coverage and configured language data"
                PreviewSection.Settings -> "Backend URL, defaults, and app behavior controls"
            },
            connected = connected,
            backendBaseUrl = backendBaseUrl,
            lastUpdatedLabel = lastUpdatedLabel
        )

        when (selectedSection) {
            PreviewSection.Dashboard -> DashboardSection(
                compactLayout = compactLayout,
                connected = connected,
                backendBaseUrl = backendBaseUrl,
                previewUrl = previewUrl,
                health = health,
                contextSummary = contextSummary,
                backendStatus = backendStatus,
                analytics = analytics,
                apiError = apiError,
                loadingOverview = loadingOverview,
                onRefresh = onRefresh
            )
            PreviewSection.Calling -> CallingSection(
                outboundCall = outboundCall,
                callHistory = callHistory.take(3),
                backendStatus = backendStatus,
                callingNow = callingNow,
                selectedLanguage = selectedLanguage,
                onLanguageSelected = onLanguageSelected,
                onPhoneNumberChange = onPhoneNumberChange,
                onDigitPressed = onDigitPressed,
                onDeleteDigit = onDeleteDigit,
                onStartOutboundCall = onStartOutboundCall,
                onEndOutboundCall = onEndOutboundCall,
                onStartDemoCall = onStartDemoCall,
                onSeeAllHistory = { onSelectedSectionChange(PreviewSection.History) }
            )
            PreviewSection.History -> HistorySection(
                callHistory = callHistory,
                compactLayout = compactLayout,
                onRefresh = onRefresh
            )
            PreviewSection.AgentTest -> AgentTestingSection(
                agentState = agentState,
                chatInput = chatInput,
                selectedLanguage = selectedLanguage,
                onLanguageSelected = onLanguageSelected,
                onChatInputChange = onChatInputChange,
                testingAgent = testingAgent,
                onStartAgentTest = onStartAgentTest,
                onSendAgentMessage = onSendAgentMessage
            )
            PreviewSection.Website -> WebsiteSection(
                previewUrl = previewUrl,
                compactLayout = compactLayout
            )
            PreviewSection.Simulators -> SimulatorsSection(
                compactLayout = compactLayout,
                backendBaseUrl = backendBaseUrl,
                previewUrl = previewUrl,
                connected = connected
            )
            PreviewSection.Analytics -> AnalyticsSection(
                contextSummary = contextSummary,
                analytics = analytics,
                compactLayout = compactLayout
            )
            PreviewSection.Settings -> SettingsSection(
                compactLayout = compactLayout,
                backendBaseUrl = backendBaseUrl,
                backendDraft = backendDraft,
                onBackendDraftChange = onBackendDraftChange,
                onApplyBackend = onApplyBackend,
                onUseDefaultBackend = onUseDefaultBackend
            )
        }
    }
}

@Composable
private fun SectionHero(
    title: String,
    description: String,
    connected: Boolean,
    backendBaseUrl: String,
    lastUpdatedLabel: String
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = HeroSurface),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(label = if (connected) "Connected now" else "Connection required", connected = connected)
                Spacer(Modifier.width(10.dp))
                Text(lastUpdatedLabel, color = SecondaryText, fontSize = 12.sp)
            }
            Text(title, color = PrimaryText, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(description, color = SecondaryText, fontSize = 14.sp)
            Text(truncateMiddle(backendBaseUrl), color = AccentText, fontSize = 13.sp)
        }
    }
}

@Composable
private fun DashboardSection(
    compactLayout: Boolean,
    connected: Boolean,
    backendBaseUrl: String,
    previewUrl: String,
    health: HealthResponse?,
    contextSummary: ContextSummaryResponse?,
    backendStatus: BackendStatusResponse?,
    analytics: AnalyticsResponse?,
    apiError: String?,
    loadingOverview: Boolean,
    onRefresh: () -> Unit
) {
    SummaryGrid(
        compactLayout = compactLayout,
        items = listOf(
            Triple("Connection", if (connected) "Live" else "Offline", if (connected) SuccessGreen else ErrorRed),
            Triple("Agent", contextSummary?.agentName ?: "Unavailable", PrimaryBlue),
            Triple("Documents", (contextSummary?.documentsLoaded ?: 0).toString(), AccentBlue),
            Triple("Chunks", (contextSummary?.totalChunks ?: 0).toString(), WarningAmber)
        )
    )

    if (loadingOverview) {
        StandardCard("Syncing backend") {
            CircularProgressIndicator(color = PrimaryBlue)
        }
    }

    apiError?.let { error ->
        StandardCard("Connection issue") {
            Text(error, color = ErrorRed, fontSize = 13.sp)
        }
    }

    StandardCard("Live Connection") {
        InfoRow("Backend URL", truncateMiddle(backendBaseUrl))
        InfoRow("Website URL", truncateMiddle(previewUrl))
        InfoRow("Health", health?.status ?: "Unavailable")
        InfoRow("Version", health?.version ?: "Unknown")
        InfoRow("Company", contextSummary?.company ?: "Unknown")
        InfoRow("Webhook Base", truncateMiddle(backendStatus?.webhookBaseUrl ?: "Unknown"))
        InfoRow("Telephony Mode", if (backendStatus?.twilioMockMode == true) "Mock" else "Live")
        InfoRow("AI Mode", backendStatus?.aiMode?.let(::uiLabel) ?: "Unknown")
        InfoRow("Model", backendStatus?.llmModel ?: "Unknown")
        InfoRow("Inbound Auto Answer", if (backendStatus?.autoAnswerInbound == true) "Ready" else "Needs public webhook")
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onRefresh, colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)) {
                Text("Refresh")
            }
            OutlinedButton(onClick = { shareLink("VoiceNova backend", backendBaseUrl) }) {
                Text("Share Backend")
            }
        }
    }

    StandardCard("Focused Workflows") {
        SurfaceNote("Calling screen", "Use the dedicated Calling tab for live outbound calls and call status details.")
        SurfaceNote("Agent Test screen", "Use the dedicated Agent Test tab to simulate the agent conversation before a real call.")
        SurfaceNote("Credits used", "${analytics?.creditsUsed ?: 0} / ${analytics?.creditsLimit ?: 0} credits used from this running backend.")
    }
}

@Composable
private fun HistorySection(
    callHistory: List<CallHistoryItem>,
    compactLayout: Boolean,
    onRefresh: () -> Unit
) {
    StandardCard("Full Call Logs") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${callHistory.size} recorded calls", color = SecondaryText, fontSize = 13.sp)
            Button(onClick = onRefresh, colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)) {
                Text("Sync Logs")
            }
        }
        Spacer(Modifier.height(16.dp))
        if (callHistory.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                Text("No call history found", color = SecondaryText)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                callHistory.forEach { item ->
                    HistoryRow(item)
                }
            }
        }
    }
}

@Composable
private fun CallingSection(
    outboundCall: OutboundCallState,
    callHistory: List<CallHistoryItem>,
    backendStatus: BackendStatusResponse?,
    callingNow: Boolean,
    selectedLanguage: String,
    onLanguageSelected: (String) -> Unit,
    onPhoneNumberChange: (String) -> Unit,
    onDigitPressed: (String) -> Unit,
    onDeleteDigit: () -> Unit,
    onStartOutboundCall: () -> Unit,
    onEndOutboundCall: () -> Unit,
    onStartDemoCall: () -> Unit,
    onSeeAllHistory: () -> Unit
) {
    StandardCard("Call Studio") {
        Text("Choose language, dial the number, and start a real or demo call. When a call connects, a full-screen call UI opens automatically.", color = SecondaryText, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))
        CallCapabilityStrip(backendStatus = backendStatus)
        Spacer(Modifier.height(14.dp))
        LanguageSelector(selectedLanguage = selectedLanguage, onLanguageSelected = onLanguageSelected)
        Spacer(Modifier.height(14.dp))
        ActiveCallShell(
            title = outboundCall.phoneNumber.ifBlank { "VoiceNova Agent" },
            subtitle = if (outboundCall.callSid == null) "Ready to dial" else "Opening call screen",
            caption = "Language: ${languageLabel(selectedLanguage)}",
            accent = SuccessGreen
        ) {
            Text("Dialed number", color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                outboundCall.phoneNumber.ifBlank { "Enter phone number" },
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))
            NeonWaveform()
        }
        Spacer(Modifier.height(18.dp))
        Row(modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center) {
            DialPad(
                onDigitPressed = onDigitPressed,
                onDeleteDigit = onDeleteDigit
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onStartOutboundCall,
                enabled = !callingNow && outboundCall.phoneNumber.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                modifier = Modifier.weight(1f)
            ) {
                Text(if (callingNow) "Calling..." else "Start Real Call")
            }
            OutlinedButton(
                onClick = onStartDemoCall,
                enabled = !callingNow,
                modifier = Modifier.weight(1f)
            ) {
                Text("Test Agent")
            }
        }
        Spacer(Modifier.height(12.dp))
        SurfaceNote(
            "Audio mode",
            if (backendStatus?.twilioMockMode == true) {
                "Telephony is in mock mode, so no real phone audio can be heard yet. Add real Twilio credentials and turn mock mode off to place a real call."
            } else {
                "This app is a call control panel. Audio is carried by the real phone call path, not by in-app speaker playback."
            }
        )
        Spacer(Modifier.height(12.dp))
        SurfaceNote(
            "Control capabilities",
            callControlsDescription(backendStatus)
        )
        outboundCall.statusMessage?.let {
            Spacer(Modifier.height(12.dp))
            SurfaceNote("Latest status", it)
        }
    }

    StandardCard("Recent Calls") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Recent history", color = PrimaryText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (callHistory.isNotEmpty()) {
                Text(
                    "See All",
                    color = PrimaryBlue,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onSeeAllHistory() }
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        if (callHistory.isEmpty()) {
            Text("No call history yet. Completed and active calls will appear here.", color = SecondaryText, fontSize = 13.sp)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                callHistory.forEach { item ->
                    HistoryRow(item)
                }
            }
        }
    }
}

@Composable
private fun AgentTestingSection(
    agentState: AgentTestState,
    chatInput: String,
    selectedLanguage: String,
    onLanguageSelected: (String) -> Unit,
    onChatInputChange: (String) -> Unit,
    testingAgent: Boolean,
    onStartAgentTest: () -> Unit,
    onSendAgentMessage: () -> Unit
) {
    StandardCard("Agent Conversation Lab") {
        Text("Use this screen to test replies, language detection, and knowledge responses without starting a real call.", color = SecondaryText, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        LanguageSelector(selectedLanguage = selectedLanguage, onLanguageSelected = onLanguageSelected)
        Spacer(Modifier.height(12.dp))
        if (agentState.sessionId == null) {
            Button(
                onClick = onStartAgentTest,
                enabled = !testingAgent,
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
            ) {
                Text(if (testingAgent) "Starting..." else "Start Test Conversation")
            }
        } else {
            InfoRow("Session", agentState.sessionId)
            InfoRow("Detected language", agentState.detectedLanguage)
            Spacer(Modifier.height(12.dp))
            TranscriptCard(lines = agentState.transcript)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = chatInput,
                onValueChange = onChatInputChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Message the agent") }
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onSendAgentMessage,
                enabled = !testingAgent && chatInput.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
            ) {
                Text(if (testingAgent) "Sending..." else "Send Message")
            }
        }
    }
}

@Composable
private fun ActiveCallShell(
    title: String,
    subtitle: String,
    caption: String,
    accent: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0C1020)),
        shape = RoundedCornerShape(30.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF11172C), Color(0xFF090C18))
                    )
                )
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            StatusChip(label = subtitle, connected = accent == SuccessGreen)
            Spacer(Modifier.height(18.dp))
            Box(
                modifier = Modifier
                    .size(108.dp)
                    .background(
                        Brush.linearGradient(listOf(AccentBlue, PrimaryBlue)),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    title.take(2).uppercase(),
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(caption, color = Color.White.copy(alpha = 0.74f), fontSize = 13.sp)
            Spacer(Modifier.height(18.dp))
            content()
        }
    }
}

@Composable
private fun NeonWaveform() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        val heights = listOf(14.dp, 24.dp, 36.dp, 26.dp, 42.dp, 30.dp, 18.dp, 34.dp, 22.dp)
        heights.forEachIndexed { index, height ->
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(height)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                if (index % 2 == 0) AccentBlue else PrimaryBlue,
                                if (index % 2 == 0) PrimaryBlue else AccentBlue
                            )
                        ),
                        RoundedCornerShape(999.dp)
                    )
            )
        }
    }
}

@Composable
private fun CallControlButton(
    label: String,
    highlighted: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when {
                !enabled -> DividerColor.copy(alpha = 0.4f)
                highlighted -> SelectedSurface
                else -> AppBackground
            }
        ),
        shape = RoundedCornerShape(24.dp),
        modifier = modifier.clickable(enabled = enabled, onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        if (!enabled) DividerColor.copy(alpha = 0.5f)
                        else if (highlighted) PrimaryBlue.copy(alpha = 0.16f)
                        else DividerColor,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label.take(1),
                    color = when {
                        !enabled -> SecondaryText.copy(alpha = 0.7f)
                        highlighted -> PrimaryBlue
                        else -> SecondaryText
                    },
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                label,
                color = if (enabled) PrimaryText else SecondaryText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun AssistPanel(selectedLanguage: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppBackground),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Agent Assist", color = PrimaryText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            SurfaceNote(
                "Script / Checklist",
                "Open with the selected ${languageLabel(selectedLanguage)} greeting, confirm need, then move into plan, support, or escalation."
            )
            SurfaceNote(
                "Live assist",
                "Transcript translation and next-best-action cards will appear here when live transcript capture is connected."
            )
        }
    }
}

@Composable
private fun ActiveCallOverlay(
    outboundCall: OutboundCallState,
    selectedLanguage: String,
    telephonyMode: String,
    backendStatus: BackendStatusResponse?,
    onConnectExpert: suspend (String) -> String,
    onConnectMonitor: suspend (String) -> String,
    onEndOutboundCall: () -> Unit,
    callingNow: Boolean
) {
    var muted by remember { mutableStateOf(false) }
    var holdOn by remember { mutableStateOf(false) }
    var speakerOn by remember { mutableStateOf(false) }
    var controlMessage by remember { mutableStateOf<String?>(null) }
    var expertNumber by remember { mutableStateOf("") }
    var monitorNumber by remember { mutableStateOf("") }
    var connectingExpert by remember { mutableStateOf(false) }
    var connectingMonitor by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val isConnected = outboundCall.liveStatus == "active" || outboundCall.liveStatus == "in-progress"
    val statusLabel = when {
        outboundCall.liveStatus == "completed" -> "Finished"
        isConnected -> "Call Connected"
        else -> "Connecting..."
    }
    val realSpeakerControl = backendStatus?.callControlsMode == "sdk"
    val aiModeLabel = backendStatus?.aiMode?.let(::uiLabel) ?: "Unknown"
    val twilioSuperviseEnabled = backendStatus?.selectedVoiceBackend == "twilio_native" && telephonyMode == "Live"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF05070F))
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Live Call", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                StatusChip(label = statusLabel, connected = isConnected)
            }
            ActiveCallShell(
                title = outboundCall.phoneNumber.ifBlank { "VoiceNova Agent" },
                subtitle = statusLabel,
                caption = "Language: ${languageLabel(selectedLanguage)}  •  ${formatCallTimer(outboundCall.startedAtMillis)}",
                accent = if (isConnected) SuccessGreen else WarningAmber
            ) {
                NeonWaveform()
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CapabilityPill("AI", aiModeLabel, AccentBlue)
                CapabilityPill("Auto Answer", if (backendStatus?.autoAnswerInbound == true) "Ready" else "Pending", SuccessGreen)
                CapabilityPill("Transport", backendStatus?.callControlsMode?.let(::uiLabel) ?: "Unknown", WarningAmber)
                CapabilityPill("Telephony", telephonyMode, PrimaryBlue)
            }

            Text("Action Center", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SwipableDataCard("Agent Script", "Open with ${languageLabel(selectedLanguage)} greeting. Confirm user identity, answer from the business knowledge base, and offer callback if data is missing.", PrimaryBlue)
                SwipableDataCard("AI Engine", "${backendStatus?.llmModel ?: "Unknown model"} running in $aiModeLabel mode. This is where your local or server-hosted business agent answers from your knowledge scope.", AccentBlue)
                SwipableDataCard("Caller Profile", "No CRM profile found for ${outboundCall.phoneNumber}. Treat this as a new inbound business lead until lookup is connected.", WarningAmber)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                CallControlButton(
                    "Mute",
                    highlighted = muted,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        muted = !muted
                        controlMessage = "Mute toggled: ${if (muted) "On" else "Off"}"
                    }
                )
                CallControlButton(
                    "Hold",
                    highlighted = holdOn,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        holdOn = !holdOn
                        controlMessage = "Hold toggled: ${if (holdOn) "On" else "Off"}"
                    }
                )
                CallControlButton(
                    "Speaker",
                    highlighted = speakerOn,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (realSpeakerControl) {
                            speakerOn = !speakerOn
                            controlMessage = "Speaker toggled: ${if (speakerOn) "On" else "Off"}"
                        } else {
                            controlMessage = "Speaker routing becomes a real device control when the app uses an in-app voice SDK. In Twilio phone-number mode, audio stays on the carrier call path."
                        }
                    }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                CallControlButton(
                    "Keypad",
                    highlighted = false,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        controlMessage = "Keypad is visual-only in this version."
                    }
                )
                CallControlButton(
                    "Record",
                    highlighted = true,
                    enabled = true,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        controlMessage = "Call is being recorded for quality assurance."
                    }
                )
            }

            Text("Live Assist", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            SurfaceNote(
                "Expert and Monitor",
                if (twilioSuperviseEnabled) {
                    "Connect an expert into this live call, or ring a listen-only number that can hear the conversation."
                } else {
                    "Expert/monitor join requires Twilio Native voice backend and a live (non-mock) phone call."
                }
            )

            OutlinedTextField(
                value = expertNumber,
                onValueChange = { expertNumber = it },
                enabled = twilioSuperviseEnabled && !connectingExpert && !connectingMonitor,
                label = { Text("Expert phone number") },
                placeholder = { Text("+919876543210") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    if (expertNumber.isBlank()) {
                        controlMessage = "Enter the expert phone number first."
                        return@Button
                    }
                    scope.launch {
                        connectingExpert = true
                        controlMessage = runCatching { onConnectExpert(expertNumber.trim()) }
                            .getOrElse { userFacingError(it) }
                        connectingExpert = false
                    }
                },
                enabled = twilioSuperviseEnabled && !connectingExpert && !connectingMonitor,
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (connectingExpert) "Connecting Expert..." else "Connect Expert", color = Color.White)
            }

            OutlinedTextField(
                value = monitorNumber,
                onValueChange = { monitorNumber = it },
                enabled = twilioSuperviseEnabled && !connectingExpert && !connectingMonitor,
                label = { Text("Listen-only phone number") },
                placeholder = { Text("+919876543210") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    if (monitorNumber.isBlank()) {
                        controlMessage = "Enter the listen-only phone number first."
                        return@Button
                    }
                    scope.launch {
                        connectingMonitor = true
                        controlMessage = runCatching { onConnectMonitor(monitorNumber.trim()) }
                            .getOrElse { userFacingError(it) }
                        connectingMonitor = false
                    }
                },
                enabled = twilioSuperviseEnabled && !connectingExpert && !connectingMonitor,
                colors = ButtonDefaults.buttonColors(containerColor = CardSurface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (connectingMonitor) "Connecting Listener..." else "Listen Only", color = Color.White)
            }

            AssistPanel(selectedLanguage = selectedLanguage)
            controlMessage?.let {
                SurfaceNote("Control status", it)
            }
            SurfaceNote(
                "Call controls",
                callControlsDescription(backendStatus)
            )
            SurfaceNote(
                "Audio path",
                if (telephonyMode == "Mock") {
                    "Mock telephony is enabled. Controls are visual only until real Twilio calling is enabled."
                } else {
                    "Voice is delivered through the actual phone call path. The app provides real-time control over the call session."
                }
            )
            outboundCall.callSid?.let { InfoRow("Call SID", it) }
        }

        // Fixed Hangup Button at bottom
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF05070F))
                .padding(bottom = 40.dp, top = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Button(
                    onClick = onEndOutboundCall,
                    enabled = !callingNow,
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                    shape = CircleShape,
                    modifier = Modifier.size(88.dp)
                ) {
                    Text(if (callingNow) "..." else "Hangup", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Tap to end session",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun SwipableDataCard(title: String, body: String, accent: Color) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B2E)),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.width(280.dp).height(160.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).background(accent, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Text(body, color = Color.White.copy(alpha = 0.72f), fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CallCapabilityStrip(backendStatus: BackendStatusResponse?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CapabilityPill("AI", backendStatus?.aiMode?.let(::uiLabel) ?: "Unknown", AccentBlue)
        CapabilityPill("Model", truncateMiddle(backendStatus?.llmModel ?: "Unknown"), PrimaryBlue)
        CapabilityPill("Inbound", if (backendStatus?.autoAnswerInbound == true) "Auto Answer Ready" else "Public URL Needed", SuccessGreen)
        CapabilityPill("Controls", backendStatus?.callControlsMode?.let(::uiLabel) ?: "Unknown", WarningAmber)
    }
}

@Composable
private fun CapabilityPill(label: String, value: String, accent: Color) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101626)),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(label, color = Color.White.copy(alpha = 0.58f), fontSize = 11.sp)
            Text(value, color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun HistoryRow(item: CallHistoryItem) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppBackground),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${item.from} → ${item.to}", color = PrimaryText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(item.direction.replaceFirstChar { it.uppercase() }, color = SecondaryText, fontSize = 12.sp)
                Text(item.status.replaceFirstChar { it.uppercase() }, color = AccentText, fontSize = 12.sp)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Language ${languageLabel(item.language)}", color = SecondaryText, fontSize = 12.sp)
                Text("${item.durationSeconds}s", color = PrimaryText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun WebsiteSection(
    previewUrl: String,
    compactLayout: Boolean
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Live Website", color = PrimaryText, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(truncateMiddle(previewUrl), color = AccentText, fontSize = 13.sp)
                }
                if (compactLayout) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
                        Button(onClick = { shareLink("VoiceNova website", previewUrl) }, colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)) {
                            Text("Share Link")
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { shareLink("VoiceNova website", previewUrl) }, colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)) {
                            Text("Share Link")
                        }
                    }
                }
            }
            Text("This page is isolated from the dashboard shell so you can inspect the complete website more clearly.", color = SecondaryText, fontSize = 13.sp)
            WebsitePreview(
                url = previewUrl,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (compactLayout) 680.dp else 860.dp)
            )
        }
    }
}

@Composable
private fun SimulatorsSection(
    compactLayout: Boolean,
    backendBaseUrl: String,
    previewUrl: String,
    connected: Boolean
) {
    StandardCard("Current Device Route") {
        InfoRow("Applied backend", truncateMiddle(backendBaseUrl))
        InfoRow("Website page", truncateMiddle(previewUrl))
        InfoRow("Route type", routeTypeFor(backendBaseUrl))
        InfoRow("Connection", if (connected) "Verified" else "Pending")
    }

    StandardCard("Recommended Paths") {
        val routes = listOf(
            "Real device" to "Use the public ngrok URL so both API calls and website preview work the same way.",
            "Android emulator" to "Use http://10.0.2.2:8080 only for local emulator testing without ngrok.",
            "iOS simulator" to "Use localhost when the simulator runs on the same Mac as the backend.",
            "Team sharing" to "Use Share Link from the Website page to send the live public URL."
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            routes.forEach { (title, body) ->
                SurfaceNote(title, body)
            }
        }
    }

    if (!compactLayout) {
        StandardCard("Real Call Reminder") {
            Text("For live calls, the public backend URL and Twilio webhook path must stay available while the call is active.", color = SecondaryText, fontSize = 13.sp)
        }
    }
}

@Composable
private fun AnalyticsSection(
    contextSummary: ContextSummaryResponse?,
    analytics: AnalyticsResponse?,
    compactLayout: Boolean
) {
    SummaryGrid(
        compactLayout = compactLayout,
        items = listOf(
            Triple("Documents", (contextSummary?.documentsLoaded ?: 0).toString(), PrimaryBlue),
            Triple("Credits Used", (analytics?.creditsUsed ?: 0).toString(), AccentBlue),
            Triple("Active Calls", (analytics?.activeCalls ?: 0).toString(), SuccessGreen),
            Triple("Success Rate", "${analytics?.successRate ?: 0}%", WarningAmber)
        )
    )

    StandardCard("Coverage") {
        InfoRow("Agent", contextSummary?.agentName ?: "Unknown")
        InfoRow("Company", contextSummary?.company ?: "Unknown")
        InfoRow("Credits Remaining", "${analytics?.creditsRemaining ?: 0} / ${analytics?.creditsLimit ?: 0}")
        InfoRow("Avg Call Duration", "${analytics?.avgCallSeconds ?: 0} sec")
        InfoRow("Messages Processed", (analytics?.messagesProcessed ?: 0).toString())
        Spacer(Modifier.height(16.dp))
        Text("Configured languages", color = PrimaryText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        TagList(contextSummary?.languagesConfigured?.ifEmpty { listOf("No languages loaded") } ?: listOf("No languages loaded"))
        Spacer(Modifier.height(18.dp))
        Text("Knowledge topics", color = PrimaryText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        TagList(contextSummary?.topicsCovered?.take(12)?.ifEmpty { listOf("No topics loaded") } ?: listOf("No topics loaded"))
        Spacer(Modifier.height(18.dp))
        Text("Premium analytics", color = PrimaryText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        ProgressMetric("Credits used", analytics?.creditsUsed ?: 0, analytics?.creditsLimit ?: 100, PrimaryBlue)
        Spacer(Modifier.height(10.dp))
        ProgressMetric("Completion rate", analytics?.successRate ?: 0, 100, SuccessGreen)
        Spacer(Modifier.height(10.dp))
        ProgressMetric("Current active calls", analytics?.activeCalls ?: 0, (analytics?.completedCalls ?: 0).coerceAtLeast(1), WarningAmber)
        Spacer(Modifier.height(18.dp))
        Text("Top intents", color = PrimaryText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        TagList(analytics?.topIntents?.ifEmpty { listOf("No intents yet") } ?: listOf("No intents yet"))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsSection(
    compactLayout: Boolean,
    backendBaseUrl: String,
    backendDraft: String,
    onBackendDraftChange: (String) -> Unit,
    onApplyBackend: () -> Unit,
    onUseDefaultBackend: () -> Unit
) {
    var autoReload by remember { mutableStateOf(true) }
    var allowLocalHttp by remember { mutableStateOf(true) }
    var cleanWebsiteShell by remember { mutableStateOf(true) }

    StandardCard("Backend Setup") {
        InfoRow("Applied backend", truncateMiddle(backendBaseUrl))
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = backendDraft,
            onValueChange = onBackendDraftChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Backend base URL") },
            singleLine = true
        )
        Spacer(Modifier.height(12.dp))
        if (compactLayout) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickUrlButton("Default", defaultBackendBaseUrl(), onBackendDraftChange)
                QuickUrlButton("Ngrok", "https://semiotic-unprocessional-misha.ngrok-free.dev", onBackendDraftChange)
                QuickUrlButton("Android Local", "http://10.0.2.2:8080", onBackendDraftChange)
                QuickUrlButton("LAN Example", "http://192.168.1.10:8080", onBackendDraftChange)
            }
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                QuickUrlButton("Default", defaultBackendBaseUrl(), onBackendDraftChange)
                QuickUrlButton("Ngrok", "https://semiotic-unprocessional-misha.ngrok-free.dev", onBackendDraftChange)
                QuickUrlButton("Android Local", "http://10.0.2.2:8080", onBackendDraftChange)
                QuickUrlButton("LAN Example", "http://192.168.1.10:8080", onBackendDraftChange)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onApplyBackend, colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)) {
                Text("Apply URL")
            }
            OutlinedButton(onClick = onUseDefaultBackend) {
                Text("Use Default")
            }
        }
    }

    StandardCard("App Behavior") {
        SettingRow("Auto refresh dashboard", "Refresh connection cards when backend changes", autoReload) { autoReload = it }
        Spacer(Modifier.height(10.dp))
        SettingRow("Allow local HTTP", "Keep emulator-only local URLs available when needed", allowLocalHttp) { allowLocalHttp = it }
        Spacer(Modifier.height(10.dp))
        SettingRow("Dedicated website page", "Keep website preview isolated from the dashboard shell", cleanWebsiteShell) { cleanWebsiteShell = it }
    }
}

@Composable
private fun SummaryGrid(
    compactLayout: Boolean,
    items: List<Triple<String, String, Color>>
) {
    if (compactLayout) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items.forEach { (label, value, accent) ->
                SummaryCard(label = label, value = value, accent = accent)
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items.chunked(2).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    rowItems.forEach { (label, value, accent) ->
                        SummaryCard(label = label, value = value, accent = accent, modifier = Modifier.weight(1f))
                    }
                    if (rowItems.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, color = SecondaryText, fontSize = 12.sp)
            Text(value, color = PrimaryText, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .background(accent.copy(alpha = 0.16f), RoundedCornerShape(999.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.72f)
                        .height(5.dp)
                        .background(accent, RoundedCornerShape(999.dp))
                )
            }
        }
    }
}

@Composable
private fun StandardCard(
    title: String,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardSurface),
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = PrimaryText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun SurfaceNote(title: String, body: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppBackground),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = PrimaryText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = SecondaryText, fontSize = 13.sp)
        }
    }
}

@Composable
private fun TranscriptCard(lines: List<String>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppBackground),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            lines.forEach { line ->
                Text(line, color = PrimaryText, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = SecondaryText, fontSize = 13.sp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            color = PrimaryText,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StatusChip(label: String, connected: Boolean) {
    Row(
        modifier = Modifier
            .background(
                if (connected) SuccessGreen.copy(alpha = 0.14f) else ErrorRed.copy(alpha = 0.14f),
                RoundedCornerShape(999.dp)
            )
            .border(
                1.dp,
                if (connected) SuccessGreen.copy(alpha = 0.25f) else ErrorRed.copy(alpha = 0.25f),
                RoundedCornerShape(999.dp)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(if (connected) SuccessGreen else ErrorRed, CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = if (connected) SuccessGreen else ErrorRed, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagList(items: List<String>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items.forEach { item ->
            Box(
                modifier = Modifier
                    .background(SelectedSurface, RoundedCornerShape(999.dp))
                    .border(1.dp, DividerColor, RoundedCornerShape(999.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(item, color = PrimaryText, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun QuickUrlButton(label: String, value: String, onPick: (String) -> Unit) {
    OutlinedButton(onClick = { onPick(value) }) {
        Text(label)
    }
}

@Composable
private fun SettingRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AppBackground),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = PrimaryText, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(description, color = SecondaryText, fontSize = 12.sp)
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = PrimaryBlue
                )
            )
        }
    }
}

@OptIn( ExperimentalLayoutApi::class)
@Composable
private fun DialPad(
    onDigitPressed: (String) -> Unit,
    onDeleteDigit: () -> Unit
) {
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
    ) {
        keys.forEach { key ->
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .background(AppBackground, CircleShape)
                    .border(1.dp, DividerColor, CircleShape)
                    .clickable { onDigitPressed(key) },
                contentAlignment = Alignment.Center
            ) {
                Text(key, color = PrimaryText, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        OutlinedButton(onClick = onDeleteDigit, modifier = Modifier.fillMaxWidth()) {
            Text("Delete Last Digit")
        }
    }
}

@Composable
private fun ProgressMetric(
    label: String,
    value: Int,
    total: Int,
    accent: Color
) {
    val safeTotal = total.coerceAtLeast(1)
    val progress = (value.toFloat() / safeTotal.toFloat()).coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = SecondaryText, fontSize = 13.sp)
            Text("$value / $safeTotal", color = PrimaryText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(accent.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(8.dp)
                    .background(accent, RoundedCornerShape(999.dp))
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageSelector(
    selectedLanguage: String,
    onLanguageSelected: (String) -> Unit
) {
    val options = listOf(
        "hi-IN" to "Hindi",
        "en-IN" to "English",
        "ta-IN" to "Tamil",
        "te-IN" to "Telugu"
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        options.forEach { (code, label) ->
            val selected = selectedLanguage == code
            OutlinedButton(
                onClick = { onLanguageSelected(code) },
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (selected) SelectedSurface else Color.Transparent
                )
            ) {
                Text(label, color = if (selected) PrimaryBlue else PrimaryText)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlGrid() {
    val controls = listOf("Mute", "Hold", "Keypad", "Transfer")
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        controls.forEach { control ->
            Card(
                colors = CardDefaults.cardColors(containerColor = AppBackground),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.width(132.dp)
            ) {
                Box(
                    modifier = Modifier.padding(vertical = 18.dp).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(control, color = SecondaryText, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

private fun routeTypeFor(url: String): String = when {
    "ngrok" in url -> "Public ngrok tunnel"
    "10.0.2.2" in url -> "Android emulator loopback"
    "localhost" in url || "127.0.0.1" in url -> "Local simulator path"
    "192.168." in url || "172." in url || "10." in url -> "LAN device path"
    else -> "Custom endpoint"
}

private fun languageLabel(code: String): String = when (code) {
    "hi-IN" -> "Hindi"
    "en-IN" -> "English"
    "ta-IN" -> "Tamil"
    "te-IN" -> "Telugu"
    else -> code
}

private fun uiLabel(value: String): String =
    value.replace('_', ' ').replaceFirstChar { it.uppercase() }

private fun callControlsDescription(backendStatus: BackendStatusResponse?): String {
    return when (backendStatus?.callControlsMode) {
        "preview" -> "Call controls are visual preview controls until live Twilio calling is turned on."
        "pstn" -> "This build manages PSTN business calls through Twilio webhooks. Hangup is real, while speaker routing belongs to the caller's phone unless you move to a Voice SDK client."
        "sdk" -> "This backend is ready for in-app voice controls, so mute and speaker can drive the device audio session directly."
        else -> "Control capabilities will appear here after the backend reports its call transport mode."
    }
}

private fun callStatusLabel(status: String): String = when (status) {
    "active" -> "Call Active"
    "initiated" -> "Connecting"
    "mock_initiated" -> "Demo Connected"
    "completed" -> "Call Completed"
    else -> status.replaceFirstChar { it.uppercase() }
}

private fun formatCallTimer(startedAtMillis: Long?): String {
    if (startedAtMillis == null) return "00:00"
    val totalSeconds = (Clock.System.now().epochSeconds - startedAtMillis).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
}

private fun truncateMiddle(value: String, maxChars: Int = 46): String {
    if (value.length <= maxChars) return value
    val part = (maxChars - 3) / 2
    return value.take(part) + "..." + value.takeLast(part)
}

private fun userFacingError(error: Throwable): String {
    val message = error.message.orEmpty()
    return when {
        "Serializer for class" in message || "NoTransformationFoundException" in message ->
            "The backend returned an unexpected response shape. Refresh the app after installing the latest build."
        "ERR_NGROK_3200" in message || "endpoint" in message.lowercase() && "offline" in message.lowercase() ->
            "The ngrok public URL is offline right now. Start ngrok again and reuse the new live URL."
        "/api/v1/health" in message && "404" in message ->
            "The app reached the public URL, but that URL is not forwarding to the VoiceNova backend health API."
        "/api/v1/test/context-summary" in message && "500" in message ->
            "The backend context summary failed to load. Restart the backend so the latest knowledge endpoints are active."
        "/api/v1/status" in message && "404" in message ->
            "The public URL is pointing to an older backend build. Restart the backend and refresh the app."
        "Unable to resolve host" in message || "Failed to connect" in message ->
            "The device cannot reach the backend URL. Check internet access and confirm the backend/ngrok tunnel is running."
        else -> message.ifBlank { "Could not connect to the backend." }
    }
}
