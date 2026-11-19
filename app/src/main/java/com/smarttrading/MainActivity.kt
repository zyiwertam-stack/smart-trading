package com.smarttrading

import android.app.*
import android.content.*
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smarttrading.accessibility.PocketOptionAccessibilityService
import com.smarttrading.ai.AutoController
import com.smarttrading.ai.AutoControllerInput
import com.smarttrading.ai.AutoControllerState
import com.smarttrading.ai.DecisionEngine
import com.smarttrading.capture.CapturePhase
import com.smarttrading.capture.CaptureFrequency
import com.smarttrading.capture.CaptureRuntime
import com.smarttrading.capture.ScreenCaptureService
import com.smarttrading.capture.VideoScanDuration
import com.smarttrading.domain.*
import com.smarttrading.overlay.FloatingAnalysisRuntime
import com.smarttrading.overlay.FloatingWindowService
import com.smarttrading.risk.RiskManager
import com.smarttrading.risk.RiskState
import com.smarttrading.trading.BlockedPocketOptionAdapter
import com.smarttrading.vision.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.Duration

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SmartTradingApp() }
    }

}

class TradingViewModel : ViewModel() {
    var account by mutableStateOf(AccountType.DEMO); private set
    var mode by mutableStateOf(TradingMode.MANUAL); private set
    var pair by mutableStateOf("EUR/USD"); private set
    var stake by mutableStateOf("1.00"); private set
    var scanDuration by mutableStateOf(VideoScanDuration.TEN_SECONDS); private set
    var captureFrequency by mutableStateOf(CaptureFrequency.TWO_FPS); private set
    var status by mutableStateOf("Ready for DEMO / MANUAL")
    var decision by mutableStateOf<Decision?>(null); private set
    var captureStatus by mutableStateOf(CaptureRuntime.status.value); private set
    var autoRunning by mutableStateOf(false); private set
    var autoPermissionGranted by mutableStateOf(false); private set
    var autoState by mutableStateOf(AutoControllerState.DISABLED); private set
    val decisionHistory: SnapshotStateList<Decision> = mutableStateListOf()
    var marketAnalysis by mutableStateOf<MarketAnalysis?>(null); private set
    var accessibilityStatus by mutableStateOf(PocketOptionAccessibilityService.status.value); private set
    var analysisError by mutableStateOf<String?>(null); private set
    val engine = DecisionEngine()
    private val autoController = AutoController()
    private val executionAdapter = BlockedPocketOptionAdapter()
    private val openCvProcessor = OpenCvProcessor()
    private val marketAnalysisEngine = MarketAnalysisEngine()
    val screenVisionReady: Boolean
        get() = captureStatus.phase == CapturePhase.CAPTURING &&
            captureStatus.lastFrameAt?.let {
                Duration.between(it, Instant.now()).toMillis() in 0..2_000
            } == true

    init {
        viewModelScope.launch {
            CaptureRuntime.status.collect {
                captureStatus = it
                updateFloatingAnalysis()
            }
        }
        viewModelScope.launch {
            PocketOptionAccessibilityService.status.collect {
                accessibilityStatus = it
                updateFloatingAnalysis()
            }
        }
        viewModelScope.launch {
            CaptureRuntime.frames.conflate().collect { captured ->
                try {
                    val processed = withContext(Dispatchers.Default) {
                        openCvProcessor.process(captured.bitmap, captured.capturedAt)
                    }
                    val result = withContext(Dispatchers.Default) {
                        marketAnalysisEngine.analyze(
                            processed,
                            FloatingAnalysisRuntime.excludedRegions(processed.frame.width, processed.frame.height)
                        )
                    }
                    marketAnalysis = result
                    analysisError = null
                    publishDecision(decisionFor(result))
                    status = if (result.chart.status == ChartStatus.DETECTED) {
                        "Analysis updated from captured screen frame"
                    } else {
                        "WAIT: ${result.chart.reasons.joinToString()}"
                    }
                    updateFloatingAnalysis()
                } catch (error: Exception) {
                    analysisError = "Frame analysis failed: ${error.message ?: error.javaClass.simpleName}"
                    publishDecision(waitDecision(analysisError!!))
                    status = analysisError!!
                    updateFloatingAnalysis()
                } catch (error: LinkageError) {
                    analysisError = "OpenCV runtime unavailable: ${error.message ?: error.javaClass.simpleName}"
                    publishDecision(waitDecision(analysisError!!))
                    status = analysisError!!
                    updateFloatingAnalysis()
                } finally {
                    if (!captured.bitmap.isRecycled) captured.bitmap.recycle()
                }
            }
            viewModelScope.launch {
                while (isActive) {
                    delay(500)
                    val latest = marketAnalysis?.capturedAt
                    val age = latest?.let { Duration.between(it, Instant.now()).toMillis() }
                    if (age != null && age !in 0..MAX_LIVE_FRAME_AGE_MS &&
                        decision?.direction != SignalDirection.WAIT
                    ) {
                        publishDecision(waitDecision("Latest analyzed frame is stale"))
                        status = "WAIT: latest analyzed frame is stale"
                        updateFloatingAnalysis()
                    }
                }
            }
        }
    }

    fun updateAccount(v: AccountType) { account = v; status = if (v == AccountType.REAL) "REAL requires explicit confirmation; execution BLOCKED" else "DEMO selected" }
    fun updateMode(v: TradingMode) {
        mode = v
        if (v == TradingMode.MANUAL) {
            autoRunning = false
            autoState = AutoControllerState.DISABLED
        }
    }
    fun analyze() {
        val result = marketAnalysis
        if (result == null) {
            publishDecision(waitDecision(analysisError ?: "No analyzed frame is available. Start Screen Vision and wait for a current frame."))
            status = decision?.explanation ?: "Analysis unavailable"
        } else {
            publishDecision(decisionFor(result))
            status = decision?.explanation ?: "Analysis completed"
        }
        updateFloatingAnalysis()
    }
    fun stopAll() {
        autoRunning = false
        autoState = AutoControllerState.STOPPED
        status = "STOP ALL: automatic cycle stopped"
        updateFloatingAnalysis()
    }
    fun toggleAuto() {
        autoState = AutoControllerState.CHECKING
        val assessment = autoController.checkReadiness(
            AutoControllerInput(
                mode, autoPermissionGranted, account, marketAnalysis, decision,
                accessibilityStatus.enabled, accessibilityStatus.snapshot, executionAdapter.status(),
                RiskManager(RiskSettings(stake = stake.toDoubleOrNull() ?: 0.0)), RiskState()
            )
        )
        autoState = assessment.state
        autoRunning = false
        status = "${assessment.state}: ${assessment.reason}"
        updateFloatingAnalysis()
    }
    fun updatePair(v: String) { pair = v }
    fun updateStake(v: String) { stake = v }
    fun updateScanDuration(v: VideoScanDuration) { scanDuration = v }
    fun updateCaptureFrequency(v: CaptureFrequency) { captureFrequency = v }
    fun setAutoPermission(granted: Boolean) {
        autoPermissionGranted = granted
        if (!granted) autoState = AutoControllerState.DISABLED
        status = if (granted) "AUTO evaluation permission granted; trade execution remains gated" else "AUTO evaluation permission disabled"
        updateFloatingAnalysis()
    }

    fun decisionFor(analysis: MarketAnalysis): Decision {
        val currentAge = Duration.between(analysis.capturedAt, Instant.now()).toMillis()
        val trendValue = when (analysis.trend.direction) {
            TrendDirection.UPTREND, TrendDirection.STRONG_UPTREND -> SignalDirection.CALL
            TrendDirection.DOWNTREND, TrendDirection.STRONG_DOWNTREND -> SignalDirection.PUT
            else -> SignalDirection.WAIT
        }
        val momentumValue = when (analysis.momentum.state) {
            MomentumState.BULLISH -> SignalDirection.CALL
            MomentumState.BEARISH -> SignalDirection.PUT
            else -> SignalDirection.WAIT
        }
        val confirmedPattern = analysis.patterns
            .filter { it.quality >= 0.72 && it.direction != PatternDirection.NEUTRAL }
            .maxByOrNull { it.detectedAt }
        val patternValue = when (confirmedPattern?.direction) {
            PatternDirection.BULLISH -> SignalDirection.CALL
            PatternDirection.BEARISH -> SignalDirection.PUT
            else -> SignalDirection.WAIT
        }
        val contextValue = when (analysis.condition.state) {
            MarketState.TRENDING, MarketState.BREAKOUT -> trendValue
            else -> SignalDirection.WAIT
        }
        val moduleValues = mapOf(
            "Trend analysis" to (trendValue to analysis.trend.confidence),
            "Momentum" to (momentumValue to analysis.momentum.confidence),
            "Candle patterns" to (patternValue to (confirmedPattern?.quality ?: 0.0)),
            "Market condition" to (contextValue to analysis.condition.confidence),
            "Support / resistance" to (SignalDirection.WAIT to analysis.supportResistance.quality),
            "Relative volatility" to (SignalDirection.WAIT to analysis.candles.quality)
        )
        val engineResults = analysis.engines.map { detail ->
            val (direction, confidence) = moduleValues[detail.name]
                ?: (SignalDirection.WAIT to detail.confidence)
            com.smarttrading.domain.EngineResult(
                engine = detail.name,
                value = direction.name,
                confidence = confidence.coerceIn(0.0, 1.0),
                explanation = detail.reasons.joinToString("; "),
                timestamp = detail.analyzedAt,
                sourceData = detail.inputs + ("rawResult" to detail.result),
                recognitionQuality = detail.recognitionQuality,
                error = detail.error
            )
        }
        val snapshot = AnalysisSnapshot(
            pair = pair,
            timeframe = "visual",
            capturedAt = analysis.capturedAt,
            frameAgeMs = currentAge,
            imageQuality = analysis.chart.quality.coerceIn(0.0, 1.0),
            engines = engineResults,
            chartDetected = analysis.chart.status == ChartStatus.DETECTED && !analysis.chart.obscured,
            recognizedCandleCount = analysis.candles.candles.size
        )
        return engine.decide(snapshot, 0.72)
    }

    private fun waitDecision(reason: String) = Decision(
        SignalDirection.WAIT, pair, null, 0.0, 1.0, reason, emptyList(), Instant.now()
    )

    private fun publishDecision(value: Decision) {
        decision = value
        decisionHistory.add(0, value)
        while (decisionHistory.size > MAX_DECISION_HISTORY) decisionHistory.removeAt(decisionHistory.lastIndex)
    }

    private fun updateFloatingAnalysis() {
        val analysis = marketAnalysis
        val currentDecision = decision
        FloatingAnalysisRuntime.update(
            listOf(
                "SMART TRADING • ${currentDecision?.direction?.name ?: "WAIT"}",
                "Chart: ${analysis?.chart?.status?.name ?: "NOT ANALYZED"}  Quality: ${analysis?.chart?.quality?.let { "%.0f%%".format(it * 100) } ?: "—"}",
                "Candles: ${analysis?.candles?.candles?.size ?: 0} • Trend: ${analysis?.trend?.direction?.name ?: "UNKNOWN"}",
                "Momentum: ${analysis?.momentum?.state?.name ?: "UNKNOWN"} • Volatility: ${analysis?.volatility?.relativeState?.name ?: "UNKNOWN"}",
                "Patterns: ${analysis?.patterns?.joinToString { it.name }?.ifEmpty { "none" } ?: "none"}",
                "S/R: ${analysis?.supportResistance?.supports?.size ?: 0}/${analysis?.supportResistance?.resistances?.size ?: 0} • Confidence: ${currentDecision?.confidence?.let { "%.0f%%".format(it * 100) } ?: "0%"}",
                "WAIT: ${currentDecision?.explanation ?: "No analyzed frame"}",
                "Accessibility: ${if (accessibilityStatus.enabled) "ON" else "OFF"} • AUTO: ${autoState.name}"
            ).joinToString("\n")
        )
    }

    private companion object {
        const val MAX_DECISION_HISTORY = 20
        const val MAX_LIVE_FRAME_AGE_MS = 2_000L
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SmartTradingApp(vm: TradingViewModel = viewModel()) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xff4dd0e1), background = Color(0xff0b1118), surface = Color(0xff111c26))) {
        Scaffold(topBar = { TopAppBar(title = { Text("SMART TRADING") }, actions = { Text(if (vm.autoRunning) "AUTO ON" else "IDLE", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp)) }) }) { pad ->
            LazyColumn(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { StatusCard(vm) }
                item { AccountModeCard(vm) }
                item { SettingsCard(vm) }
                item { ActionCard(vm) }
                item { SignalCard(vm.decision) }
                item { AnalysisDetailsCard(vm.marketAnalysis, vm.analysisError) }
                item { DecisionHistoryCard(vm.decisionHistory) }
                item { AccessibilityCard(vm) }
                item { DiagnosticsCard(vm) }
            }
        }
    }
}

@Composable private fun StatusCard(vm: TradingViewModel) {
    Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(vm.status, style = MaterialTheme.typography.titleMedium)
        val captureState = when {
            vm.screenVisionReady -> "WORKING"
            vm.captureStatus.phase == CapturePhase.ERROR -> "ERROR"
            vm.captureStatus.phase == CapturePhase.STARTING ||
                vm.captureStatus.phase == CapturePhase.CAPTURING -> "WAITING FOR FRAME"
            else -> "NOT INITIALIZED"
        }
        StatusLine("SCREEN VISION", captureState, vm.screenVisionReady)
        val analysisState = when {
            vm.analysisError != null -> "ERROR"
            vm.screenVisionReady && vm.marketAnalysis != null -> "WORKING"
            vm.marketAnalysis != null -> "WAITING FOR FRESH FRAME"
            else -> "WAITING FOR FRAME"
        }
        StatusLine("AI DECISION ENGINE", analysisState, analysisState == "WORKING")
        StatusLine("POCKET OPTION", "BLOCKED / no confirmed API", false)
    } }
}
@Composable private fun StatusLine(name: String, value: String, good: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(name); Text(value, color = if (good) Color(0xff68d391) else Color(0xffffb86b)) }
}
@Composable private fun AccountModeCard(vm: TradingViewModel) {
    var confirmReal by remember { mutableStateOf(false) }
    Card { Column(Modifier.padding(16.dp)) {
        Text("ACCOUNT TYPE", style = MaterialTheme.typography.labelLarge)
        Row { AccountType.values().forEach { v ->
            FilterChip(
                selected = vm.account == v,
                onClick = { if (v == AccountType.REAL) confirmReal = true else vm.updateAccount(v) },
                label = { Text(v.name) },
                modifier = Modifier.padding(end = 8.dp)
            )
        } }
        Text("TRADING MODE", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
        Row { TradingMode.values().forEach { v -> FilterChip(selected = vm.mode == v, onClick = { vm.updateMode(v) }, label = { Text(v.name) }, modifier = Modifier.padding(end = 8.dp)) } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = vm.autoPermissionGranted, onCheckedChange = vm::setAutoPermission)
            Text("Allow AUTO readiness checks (execution remains gated)")
        }
    } }
    if (confirmReal) {
        AlertDialog(
            onDismissRequest = { confirmReal = false },
            title = { Text("Switch to REAL account?") },
            text = { Text("This only changes the selected account type. Trading execution remains blocked unless separately verified.") },
            confirmButton = {
                TextButton(onClick = { vm.updateAccount(AccountType.REAL); confirmReal = false }) {
                    Text("CONFIRM REAL")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReal = false }) { Text("CANCEL") }
            }
        )
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SettingsCard(vm: TradingViewModel) {
    var pairExpanded by remember { mutableStateOf(false) }
    var durationExpanded by remember { mutableStateOf(false) }
    var frequencyExpanded by remember { mutableStateOf(false) }
    Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ExposedDropdownMenuBox(expanded = pairExpanded, onExpandedChange = { pairExpanded = !pairExpanded }) {
            OutlinedTextField(vm.pair, { }, readOnly = true, label = { Text("Currency pair") }, modifier = Modifier.menuAnchor().fillMaxWidth())
            ExposedDropdownMenu(expanded = pairExpanded, onDismissRequest = { pairExpanded = false }) {
                listOf("EUR/USD", "GBP/USD", "USD/JPY").forEach { p -> DropdownMenuItem(text = { Text(p) }, onClick = { vm.updatePair(p); pairExpanded = false }) }
            }
        }
        OutlinedTextField(vm.stake, vm::updateStake, label = { Text("Stake (DEMO)") }, singleLine = true)
        ExposedDropdownMenuBox(
            expanded = durationExpanded,
            onExpandedChange = { durationExpanded = !durationExpanded }
        ) {
            OutlinedTextField(
                vm.scanDuration.label,
                {},
                readOnly = true,
                label = { Text("Video scan duration") },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = durationExpanded,
                onDismissRequest = { durationExpanded = false }
            ) {
                VideoScanDuration.values().forEach { duration ->
                    DropdownMenuItem(
                        text = { Text(duration.label) },
                        onClick = {
                            vm.updateScanDuration(duration)
                            durationExpanded = false
                        }
                    )
                }
            }
        }
        ExposedDropdownMenuBox(
            expanded = frequencyExpanded,
            onExpandedChange = { frequencyExpanded = !frequencyExpanded }
        ) {
            OutlinedTextField(
                vm.captureFrequency.label,
                {},
                readOnly = true,
                label = { Text("Capture frequency") },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = frequencyExpanded,
                onDismissRequest = { frequencyExpanded = false }
            ) {
                CaptureFrequency.values().forEach { frequency ->
                    DropdownMenuItem(
                        text = { Text(frequency.label) },
                        onClick = {
                            vm.updateCaptureFrequency(frequency)
                            frequencyExpanded = false
                        }
                    )
                }
            }
        }
    } }
}
@Composable private fun ActionCard(vm: TradingViewModel) {
    val context = LocalContext.current
    val projection = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenCaptureService.EXTRA_DATA, result.data)
                putExtra(ScreenCaptureService.EXTRA_SCAN_DURATION_MS, vm.scanDuration.durationMillis)
                putExtra(ScreenCaptureService.EXTRA_FRAME_INTERVAL_MS, vm.captureFrequency.intervalMillis)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        } else {
            CaptureRuntime.stopped("Screen capture permission was not granted")
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                if (vm.captureStatus.phase == CapturePhase.CAPTURING ||
                    vm.captureStatus.phase == CapturePhase.STARTING
                ) {
                    context.startService(
                        Intent(context, ScreenCaptureService::class.java)
                            .setAction(ScreenCaptureService.ACTION_STOP)
                    )
                } else {
                    projection.launch(
                        context.getSystemService(MediaProjectionManager::class.java)
                            .createScreenCaptureIntent()
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Videocam, null)
            Spacer(Modifier.width(8.dp))
            Text(
                if (vm.captureStatus.phase == CapturePhase.CAPTURING ||
                    vm.captureStatus.phase == CapturePhase.STARTING
                ) "STOP SCREEN VISION" else "START SCREEN VISION"
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::analyze, modifier = Modifier.weight(1f)) { Text("AI ANALYSIS") }
            Button(
                onClick = { vm.toggleAuto() },
                modifier = Modifier.weight(1f),
                enabled = vm.mode == TradingMode.AUTO && !vm.autoRunning
            ) { Text("START AUTO") }
        }
        OutlinedButton(
            onClick = {
                vm.stopAll()
                context.startService(
                    Intent(context, ScreenCaptureService::class.java)
                        .setAction(ScreenCaptureService.ACTION_STOP)
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("STOP ALL") }
        OutlinedButton(onClick = { if (Settings.canDrawOverlays(context)) context.startService(Intent(context, FloatingWindowService::class.java)) else context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }, modifier = Modifier.fillMaxWidth()) { Text("OPEN FLOATING WINDOW") }
    }
}
@Composable private fun AnalysisDetailsCard(analysis: MarketAnalysis?, error: String?) {
    Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("AI ANALYSIS DETAILS", style = MaterialTheme.typography.titleMedium)
        if (analysis == null) Text(error ?: "Waiting for a fresh captured frame")
        else {
            Text("Chart: ${analysis.chart.status} • ${analysis.chart.width}×${analysis.chart.height} • quality ${(analysis.chart.quality * 100).toInt()}%")
            Text("Candles: ${analysis.candles.candles.size} • ${analysis.candles.reasons.joinToString()}")
            Text("Trend: ${analysis.trend.direction} • Momentum: ${analysis.momentum.state} • Volatility: ${analysis.volatility.relativeState}")
            Text("Market: ${analysis.condition.state} • Support/resistance zones: ${analysis.supportResistance.supports.size}/${analysis.supportResistance.resistances.size}")
            Text("Patterns: ${analysis.patterns.joinToString { "${it.name} (${(it.quality * 100).toInt()}%)" }.ifEmpty { "None confirmed" }}")
            analysis.engines.forEach { detail ->
                Text("${detail.name}: ${detail.result} • confidence ${(detail.confidence * 100).toInt()}% • quality ${(detail.recognitionQuality * 100).toInt()}%")
                Text("Updated ${detail.analyzedAt} • ${detail.reasons.joinToString("; ")}", style = MaterialTheme.typography.bodySmall)
                detail.error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
            }
            analysis.chart.reasons.forEach { Text("Chart note: $it") }
        }
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
    } }
}

@Composable private fun AccessibilityCard(vm: TradingViewModel) {
    val context = LocalContext.current
    Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("POCKET OPTION ACCESSIBILITY", style = MaterialTheme.typography.titleMedium)
        Text(vm.accessibilityStatus.detail)
        val snapshot = vm.accessibilityStatus.snapshot
        Text("Screen: ${snapshot?.screen ?: "UNKNOWN"} • Account: ${snapshot?.accountType ?: "UNKNOWN"}")
        Text("SMART TRADING never requests or reads account passwords.")
        OutlinedButton(
            onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("OPEN ACCESSIBILITY SETTINGS") }
    } }
}
@Composable private fun SignalCard(d: Decision?) { Card { Column(Modifier.padding(16.dp)) { Text("CURRENT SIGNAL", style = MaterialTheme.typography.labelLarge); Text(d?.direction?.name ?: "WAIT", style = MaterialTheme.typography.headlineMedium, color = if (d?.direction == SignalDirection.CALL) Color(0xff68d391) else if (d?.direction == SignalDirection.PUT) Color(0xffff7777) else Color(0xffffb86b)); Text(d?.explanation ?: "Run AI ANALYSIS after granting Screen Vision") } } }
@Composable private fun DecisionHistoryCard(history: List<Decision>) {
    Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("RECENT ANALYTICAL DECISIONS", style = MaterialTheme.typography.titleMedium)
        if (history.isEmpty()) Text("No decisions yet")
        history.take(10).forEach { decision ->
            Text("${decision.analyzedAt} • ${decision.pair} • ${decision.direction} • ${(decision.confidence * 100).toInt()}%")
            Text(decision.explanation, style = MaterialTheme.typography.bodySmall)
        }
    } }
}
@Composable private fun DiagnosticsCard(vm: TradingViewModel) {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text("DIAGNOSTICS", style = MaterialTheme.typography.titleMedium)
            val captureState = when {
                vm.screenVisionReady -> "WORKING"
                vm.captureStatus.phase == CapturePhase.ERROR -> "ERROR"
                vm.captureStatus.phase == CapturePhase.STARTING ||
                    vm.captureStatus.phase == CapturePhase.CAPTURING -> "WAITING FOR FRAME"
                else -> "NOT INITIALIZED"
            }
            StatusLine("MEDIA PROJECTION", captureState, vm.screenVisionReady)
            listOf(
                "COMPUTER VISION" to if (vm.marketAnalysis != null) "WORKING" else "WAITING FOR FRAME",
                "CANDLESTICK ENGINE" to if ((vm.marketAnalysis?.candles?.candles?.size ?: 0) >= 3) "WORKING" else "INSUFFICIENT DATA",
                "TREND ENGINE" to (vm.marketAnalysis?.trend?.direction?.name ?: "WAITING"),
                "PATTERN RECOGNITION" to (vm.marketAnalysis?.patterns?.size?.toString() ?: "WAITING"),
                "MOMENTUM ENGINE" to (vm.marketAnalysis?.momentum?.state?.name ?: "WAITING"),
                "VOLATILITY ENGINE" to (vm.marketAnalysis?.volatility?.relativeState?.name ?: "WAITING"),
                "AI DECISION ENGINE" to (vm.decision?.direction?.name ?: "WAITING"),
                "RISK MANAGEMENT" to "NOT INITIALIZED",
                "ROOM DATABASE" to "NOT INITIALIZED",
                "POCKET OPTION INTERFACE" to (vm.accessibilityStatus.snapshot?.screen?.name ?: "NOT DETECTED"),
                "TRADE MONITOR" to "NOT INITIALIZED",
                "AUTO CONTROLLER" to vm.autoState.name
            ).forEach { (name, value) -> StatusLine(name, value, value == "WORKING") }
            StatusLine("ACCESSIBILITY SERVICE", if (vm.accessibilityStatus.enabled) "CONNECTED" else "DISABLED", vm.accessibilityStatus.enabled)
            StatusLine("TRADING EXECUTION", "BLOCKED", false)
        }
    }
}

