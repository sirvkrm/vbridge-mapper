package com.vbridge.mapper.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vbridge.mapper.VBridgeApplication
import com.vbridge.mapper.data.model.KeybindingSlot
import com.vbridge.mapper.data.model.Preset
import com.vbridge.mapper.data.model.PresetInfo
import com.vbridge.mapper.data.repository.PresetRepository
import com.vbridge.mapper.network.ConnectionStatus
import com.vbridge.mapper.network.WebSocketBridgeClient
import com.vbridge.mapper.service.AppWatcherService
import com.vbridge.mapper.service.OverlayService
import com.vbridge.mapper.ui.theme.*
import com.vbridge.mapper.utils.AppEntry
import com.vbridge.mapper.utils.InstalledAppHelper
import com.vbridge.mapper.utils.PermissionHelper
import com.vbridge.mapper.utils.InjectorManager
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var repository: PresetRepository
    private lateinit var wsClient: WebSocketBridgeClient

    private var isOverlayRunning by mutableStateOf(false)
    private var presets by mutableStateOf<List<Preset>>(emptyList())
    private var selectedPresetDetail by mutableStateOf<Preset?>(null)

    // Permissions
    private var hasOverlayPerm by mutableStateOf(false)
    private var hasUsagePerm by mutableStateOf(false)
    private var hasStoragePerm by mutableStateOf(false)

    // Dialogs
    private var showHostConfigDialog by mutableStateOf(false)
    private var showCreatePresetDialog by mutableStateOf(false)
    private var editingPresetTarget by mutableStateOf<Preset?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = (application as VBridgeApplication).presetRepository
        wsClient = (application as VBridgeApplication).wsClient

        setContent {
            VBridgeTheme {
                MainGlassScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    private fun refreshAll() {
        hasOverlayPerm = PermissionHelper.hasOverlayPermission(this)
        hasUsagePerm = PermissionHelper.hasUsageStatsPermission(this)
        hasStoragePerm = PermissionHelper.hasStoragePermission(this)
        isOverlayRunning = OverlayService.isRunning
        presets = repository.listAllPresets()

        selectedPresetDetail?.let { current ->
            selectedPresetDetail = repository.loadPreset(current.info.presetId, current.info.bindingId)
        }
    }

    private fun toggleOverlay(enable: Boolean) {
        if (enable) {
            if (!hasOverlayPerm) {
                PermissionHelper.requestOverlayPermission(this)
                return
            }
            startForegroundService(Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_START_OVERLAY
            })
            if (hasUsagePerm) {
                startForegroundService(Intent(this, AppWatcherService::class.java))
            }
            isOverlayRunning = true
        } else {
            startService(Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_STOP_OVERLAY
            })
            stopService(Intent(this, AppWatcherService::class.java))
            isOverlayRunning = false
        }
    }

    private fun launchGame(pkg: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            Toast.makeText(this, "Game '$pkg' not installed", Toast.LENGTH_SHORT).show()
        }
    }

    @Composable
    private fun MainGlassScreen() {
        val connectionState by wsClient.connectionState.collectAsState()
        val activePair by repository.activePresetFlow.collectAsState()
        val activePid = activePair.first
        val activeBid = activePair.second

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF090D14))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                // Sleek Top Toolbar
                TopGlassToolbar(connectionState)

                Spacer(modifier = Modifier.height(12.dp))

                // Master Overlay Switch Bar
                MasterSwitchCard()

                Spacer(modifier = Modifier.height(10.dp))

                // Kernel Touch Injector Status & Refresh Bar
                InjectorStatusCard()

                Spacer(modifier = Modifier.height(12.dp))

                // Missing Permissions Strip (if any)
                if (!hasOverlayPerm || !hasUsagePerm || !hasStoragePerm) {
                    PermissionMinimalStrip()
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Main Content: Either Preset Detail View or Presets List
                AnimatedContent(
                    targetState = selectedPresetDetail,
                    label = "preset_content"
                ) { detailPreset ->
                    if (detailPreset != null) {
                        PresetDetailView(
                            preset = detailPreset,
                            onBack = { selectedPresetDetail = null }
                        )
                    } else {
                        PresetsListView()
                    }
                }
            }

            // Dialogs
            if (showHostConfigDialog) {
                HostConfigDialog(onDismiss = { showHostConfigDialog = false })
            }

            if (showCreatePresetDialog || editingPresetTarget != null) {
                PresetFormDialog(
                    initialPreset = editingPresetTarget,
                    onDismiss = {
                        showCreatePresetDialog = false
                        editingPresetTarget = null
                    },
                    onSaved = {
                        refreshAll()
                        showCreatePresetDialog = false
                        editingPresetTarget = null
                    }
                )
            }
        }
    }

    @Composable
    private fun TopGlassToolbar(connectionState: ConnectionStatus) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "VBridge",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Mapper",
                    color = CyberCyan,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Light
                )
            }

            // Glowing Connection Pill
            val (glowColor, dotColor, statusText) = when (connectionState) {
                ConnectionStatus.CONNECTED -> Triple(Color(0x3300E676), Color(0xFF00E676), "CONNECTED")
                ConnectionStatus.CONNECTING -> Triple(Color(0x33FFD600), Color(0xFFFFD600), "CONNECTING")
                ConnectionStatus.DISCONNECTED -> Triple(Color(0x33FF1744), Color(0xFFFF1744), "OFFLINE")
            }

            Surface(
                onClick = { showHostConfigDialog = true },
                shape = CircleShape,
                color = Color(0xCC121A26),
                border = BorderStroke(1.dp, dotColor.copy(alpha = 0.6f)),
                shadowElevation = 4.dp,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(glowColor)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${wsClient.host}:${wsClient.port} • $statusText",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }

    @Composable
    private fun MasterSwitchCard() {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0x99141D2B),
            border = BorderStroke(1.dp, if (isOverlayRunning) CyberCyan.copy(alpha = 0.5f) else Color(0x22FFFFFF)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Floating Keymapper HUD",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (isOverlayRunning) "Bubble active on screen" else "Disabled",
                        color = if (isOverlayRunning) NeonEmerald else TextMuted,
                        fontSize = 11.sp
                    )
                }

                Switch(
                    checked = isOverlayRunning,
                    onCheckedChange = { toggleOverlay(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = CyberCyan,
                        uncheckedThumbColor = TextMuted,
                        uncheckedTrackColor = Color(0xFF1E293B)
                    )
                )
            }
        }
    }

    @Composable
    private fun InjectorStatusCard() {
        var injectorStatus by remember { mutableStateOf(InjectorManager.InjectorStatus(isRunning = false)) }
        var isRefreshing by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            injectorStatus = InjectorManager.checkStatus()
        }

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0x99141D2B),
            border = BorderStroke(1.dp, if (injectorStatus.isRunning) NeonEmerald.copy(alpha = 0.5f) else NeonCrimson.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (injectorStatus.isRunning) NeonEmerald else NeonCrimson)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Kernel Input Engine",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            val statusDesc = if (injectorStatus.isRunning) {
                                val modeLabel = when (injectorStatus.mode) {
                                    InjectorManager.EngineMode.STANDALONE_OTG -> "⚡ Standalone OTG (Zero-PC)"
                                    InjectorManager.EngineMode.PC_BRIDGE -> "💻 PC Bridge (Port 8888)"
                                    null -> "Active"
                                }
                                "$modeLabel • PID ${injectorStatus.pid}"
                            } else {
                                "Engine Inactive / Stopped"
                            }
                            Text(
                                text = statusDesc,
                                color = if (injectorStatus.isRunning) NeonEmerald else NeonCrimson,
                                fontSize = 11.sp
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            scope.launch {
                                isRefreshing = true
                                injectorStatus = InjectorManager.checkStatus()
                                isRefreshing = false
                            }
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Check Status",
                            tint = CyberCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isOtgActive = injectorStatus.isRunning && injectorStatus.mode == InjectorManager.EngineMode.STANDALONE_OTG
                    val isPcActive = injectorStatus.isRunning && injectorStatus.mode == InjectorManager.EngineMode.PC_BRIDGE

                    Button(
                        onClick = {
                            scope.launch {
                                isRefreshing = true
                                Toast.makeText(this@MainActivity, "🔌 Launching Standalone OTG Engine...", Toast.LENGTH_SHORT).show()
                                val activePid = repository.getActivePresetId()
                                val activeBid = repository.getActiveBindingId()
                                val profilePath = "/sdcard/VbridgeBind/preset$activePid/keybinding$activeBid/profile.json"
                                val res = InjectorManager.startStandaloneEngine(this@MainActivity, profilePath)
                                injectorStatus = InjectorManager.checkStatus()
                                isRefreshing = false
                                res.onSuccess {
                                    Toast.makeText(this@MainActivity, "✅ $it", Toast.LENGTH_SHORT).show()
                                }.onFailure {
                                    Toast.makeText(this@MainActivity, "❌ Failed: ${it.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        enabled = !isRefreshing,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isOtgActive) NeonEmerald.copy(alpha = 0.25f) else Color(0xFF1E293B)
                        ),
                        border = BorderStroke(1.dp, if (isOtgActive) NeonEmerald else Color(0x33FFFFFF)),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (isOtgActive) "🟢 OTG Active" else "🔌 Launch OTG",
                            color = if (isOtgActive) NeonEmerald else Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                isRefreshing = true
                                Toast.makeText(this@MainActivity, "💻 Launching PC Bridge Daemon...", Toast.LENGTH_SHORT).show()
                                val res = InjectorManager.startPcBridgeDaemon(this@MainActivity)
                                injectorStatus = InjectorManager.checkStatus()
                                isRefreshing = false
                                res.onSuccess {
                                    Toast.makeText(this@MainActivity, "✅ $it", Toast.LENGTH_SHORT).show()
                                }.onFailure {
                                    Toast.makeText(this@MainActivity, "❌ Failed: ${it.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        enabled = !isRefreshing,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPcActive) CyberCyan.copy(alpha = 0.25f) else Color(0xFF1E293B)
                        ),
                        border = BorderStroke(1.dp, if (isPcActive) CyberCyan else Color(0x33FFFFFF)),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (isPcActive) "🟢 PC Active" else "💻 Launch PC",
                            color = if (isPcActive) CyberCyan else Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun PermissionMinimalStrip() {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = Color(0x33FFD600),
            border = BorderStroke(1.dp, NeonAmber.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Grant required permissions for overlay & game detection",
                    color = Color.White,
                    fontSize = 11.sp
                )
                Button(
                    onClick = {
                        if (!hasOverlayPerm) PermissionHelper.requestOverlayPermission(this@MainActivity)
                        else if (!hasUsagePerm) PermissionHelper.requestUsageStatsPermission(this@MainActivity)
                        else if (!hasStoragePerm) PermissionHelper.requestStoragePermission(this@MainActivity)
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
                ) {
                    Text("Grant", color = NeonAmber, fontSize = 11.sp)
                }
            }
        }
    }

    @Composable
    private fun PresetsListView() {
        val activePair by repository.activePresetFlow.collectAsState()
        val activePid = activePair.first

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Presets",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                Button(
                    onClick = { showCreatePresetDialog = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CyberCyan)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Create Preset", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(presets) { preset ->
                    val isActive = preset.info.presetId == activePid
                    PresetGlassCard(
                        preset = preset,
                        isActive = isActive,
                        onClick = { selectedPresetDetail = preset },
                        onActivate = {
                            repository.setActivePreset(preset, preset.info.bindingId)
                            refreshAll()
                            if (isOverlayRunning) {
                                startService(Intent(this@MainActivity, OverlayService::class.java).apply {
                                    action = OverlayService.ACTION_SWITCH_PRESET
                                    putExtra(OverlayService.EXTRA_PRESET_ID, preset.info.presetId)
                                    putExtra(OverlayService.EXTRA_BINDING_ID, preset.info.bindingId)
                                })
                            }
                            wsClient.sendProfileActivated(
                                presetId = preset.info.presetId,
                                bindingId = preset.info.bindingId,
                                presetName = preset.info.name,
                                bindingName = preset.activeSlot.name,
                                packageName = preset.info.packageName,
                                controllerType = preset.info.controllerType,
                                via = preset.info.via,
                                path = "/sdcard/VbridgeBind/preset${preset.info.presetId}/keybinding${preset.info.bindingId}/",
                                profileJson = preset.toFullProfileJson()
                            )
                            Toast.makeText(this@MainActivity, "⚡ Active: ${preset.info.name}", Toast.LENGTH_SHORT).show()
                        },
                        onEdit = { editingPresetTarget = preset },
                        onDelete = {
                            if (presets.size <= 1) {
                                Toast.makeText(this@MainActivity, "Cannot delete last preset", Toast.LENGTH_SHORT).show()
                            } else {
                                repository.deletePreset(preset.info.presetId)
                                refreshAll()
                            }
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun AppIconStack(packages: List<String>, modifier: Modifier = Modifier) {
        val context = LocalContext.current
        val validPkgs = packages.filter { it.isNotBlank() }

        if (validPkgs.isEmpty()) {
            Box(
                modifier = modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1E293B)),
                contentAlignment = Alignment.Center
            ) {
                Text("🎮", fontSize = 20.sp)
            }
            return
        }

        if (validPkgs.size == 1) {
            val pkg = validPkgs[0]
            val iconBitmap = remember(pkg) {
                val d = InstalledAppHelper.getAppIcon(context, pkg)
                InstalledAppHelper.drawableToImageBitmap(d, 120, 120)
            }
            Box(
                modifier = modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1E293B))
                    .border(1.dp, CyberCyan.copy(alpha = 0.6f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (iconBitmap != null) {
                    Image(bitmap = iconBitmap, contentDescription = pkg, modifier = Modifier.fillMaxSize())
                } else {
                    Text("🎮", fontSize = 22.sp)
                }
            }
            return
        }

        // Multiple Packages: 3D Overlapping Stack
        // Center icon is large and in front (zIndex 2, scale 1.0, alpha 1.0).
        // Left (1st) and Right (3rd) are 70% behind it and smaller.
        Box(
            modifier = modifier
                .width(76.dp)
                .height(48.dp),
            contentAlignment = Alignment.Center
        ) {
            // 1st / Left Icon (behind)
            val leftPkg = validPkgs[0]
            val leftBmp = remember(leftPkg) {
                val d = InstalledAppHelper.getAppIcon(context, leftPkg)
                InstalledAppHelper.drawableToImageBitmap(d, 96, 96)
            }
            Box(
                modifier = Modifier
                    .offset(x = (-16).dp)
                    .size(36.dp)
                    .zIndex(1f)
                    .graphicsLayer {
                        alpha = 0.70f
                        scaleX = 0.80f
                        scaleY = 0.80f
                    }
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E293B))
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (leftBmp != null) {
                    Image(bitmap = leftBmp, contentDescription = leftPkg, modifier = Modifier.fillMaxSize())
                } else {
                    Text("🎮", fontSize = 16.sp)
                }
            }

            // 3rd / Right Icon (behind, if 3 or more apps)
            if (validPkgs.size >= 3) {
                val rightPkg = validPkgs[2]
                val rightBmp = remember(rightPkg) {
                    val d = InstalledAppHelper.getAppIcon(context, rightPkg)
                    InstalledAppHelper.drawableToImageBitmap(d, 96, 96)
                }
                Box(
                    modifier = Modifier
                        .offset(x = 16.dp)
                        .size(36.dp)
                        .zIndex(1f)
                        .graphicsLayer {
                            alpha = 0.70f
                            scaleX = 0.80f
                            scaleY = 0.80f
                        }
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E293B))
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (rightBmp != null) {
                        Image(bitmap = rightBmp, contentDescription = rightPkg, modifier = Modifier.fillMaxSize())
                    } else {
                        Text("🎮", fontSize = 16.sp)
                    }
                }
            }

            // Center Icon (in front, big, glowing)
            val centerPkg = if (validPkgs.size >= 2) validPkgs[1] else validPkgs[0]
            val centerBmp = remember(centerPkg) {
                val d = InstalledAppHelper.getAppIcon(context, centerPkg)
                InstalledAppHelper.drawableToImageBitmap(d, 120, 120)
            }
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .zIndex(2f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF141D2B))
                    .border(1.5.dp, CyberCyan, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (centerBmp != null) {
                    Image(bitmap = centerBmp, contentDescription = centerPkg, modifier = Modifier.fillMaxSize())
                } else {
                    Text("🎮", fontSize = 22.sp)
                }
            }
        }
    }

    @Composable
    private fun PresetGlassCard(
        preset: Preset,
        isActive: Boolean,
        onClick: () -> Unit,
        onActivate: () -> Unit,
        onEdit: () -> Unit,
        onDelete: () -> Unit
    ) {
        val packages = preset.info.targetPackages.ifEmpty { listOf(preset.info.packageName) }
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(14.dp),
            color = if (isActive) Color(0xB312222A) else Color(0x80121A28),
            border = BorderStroke(
                if (isActive) 1.5.dp else 1.dp,
                if (isActive) NeonEmerald else Color(0x2BFFFFFF)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Preset App Icon Stack
                AppIconStack(packages = packages)

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = preset.info.name,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (isActive) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "ACTIVE",
                                color = NeonEmerald,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .background(Color(0x3300E676), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = packages.joinToString(", "),
                        color = TextMuted,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${preset.info.controllerType} via ${preset.info.via} • Slot #${preset.info.bindingId} (${preset.activeSlot.name})",
                        color = if (isActive) CyberCyan else TextMuted,
                        fontSize = 11.sp
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!isActive) {
                        Button(
                            onClick = onActivate,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0x3300E5FF)),
                            border = BorderStroke(1.dp, CyberCyan),
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Text("⚡ Activate", color = CyberCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit", tint = TextMuted, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = NeonCrimson, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }

    @Composable
    private fun PresetDetailView(preset: Preset, onBack: () -> Unit) {
        var carouselIndex by remember(preset) {
            val idx = preset.slots.indexOfFirst { it.id == preset.info.bindingId }
            mutableIntStateOf(if (idx >= 0) idx else 0)
        }
        var renamingSlotTarget by remember { mutableStateOf<KeybindingSlot?>(null) }

        Column(modifier = Modifier.fillMaxSize()) {
            // Header with back navigation & AppIconStack
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Spacer(modifier = Modifier.width(6.dp))
                AppIconStack(packages = preset.info.targetPackages.ifEmpty { listOf(preset.info.packageName) })
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = preset.info.name,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${preset.info.controllerType} via ${preset.info.via} • ${preset.slots.size} Slots",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
                Button(
                    onClick = { launchGame(preset.info.packageName) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                    border = BorderStroke(1.dp, CyberCyan)
                ) {
                    Text("Launch", color = CyberCyan, fontSize = 11.sp)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // HUD Passthrough Lock Card
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0x33101722),
                border = BorderStroke(1.dp, if (preset.isLocked) NeonEmerald.copy(alpha = 0.6f) else CyberCyan.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (preset.isLocked) "🔒 HUD Locked (Passthrough Active)" else "🔓 HUD Unlocked (Edit Mode)",
                            color = if (preset.isLocked) NeonEmerald else CyberCyan,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (preset.isLocked) "Touches pass completely through to game underneath" else "Drag pins on screen to reposition keybindings",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = preset.isLocked,
                        onCheckedChange = { locked ->
                            preset.isLocked = locked
                            repository.savePreset(preset)
                            refreshAll()
                            if (isOverlayRunning) {
                                startService(Intent(this@MainActivity, OverlayService::class.java).apply {
                                    action = OverlayService.ACTION_SWITCH_PRESET
                                    putExtra(OverlayService.EXTRA_PRESET_ID, preset.info.presetId)
                                    putExtra(OverlayService.EXTRA_BINDING_ID, preset.info.bindingId)
                                })
                            }
                            val msg = if (locked) "🔒 Locked: In-game touch passthrough enabled" else "🔓 Unlocked: Edit mode enabled"
                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = NeonEmerald,
                            checkedTrackColor = Color(0x4000E676),
                            uncheckedThumbColor = Color.Gray,
                            uncheckedTrackColor = Color(0x33FFFFFF)
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Carousel Header Row: Title & Add Slot Button (up to 20 slots!)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Keybinding Slots (${preset.slots.size} of 20)",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )

                if (preset.slots.size < 20) {
                    Button(
                        onClick = {
                            val newSlot = preset.addNewSlot()
                            if (newSlot != null) {
                                repository.savePreset(preset)
                                refreshAll()
                                carouselIndex = preset.slots.size - 1
                                Toast.makeText(this@MainActivity, "+ Added ${newSlot.name}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x3300E5FF)),
                        border = BorderStroke(1.dp, CyberCyan)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Slot", color = CyberCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Fluid Carousel Container with Top (▼) & Bottom (▲) indicator arrows
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x33101722), RoundedCornerShape(16.dp))
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top Down-Arrow Indicator (▼) with Cyan Lighting
                Text(
                    text = "▼",
                    color = CyberCyan,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                // Carousel Cards Row: Left Card, Center (Selected) Card, Right Card
                val safeCenterIndex = carouselIndex.coerceIn(0, preset.slots.size - 1)
                val currentSlot = preset.slots[safeCenterIndex]

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left Adjacent Card (if exists)
                    if (safeCenterIndex > 0) {
                        val prevSlot = preset.slots[safeCenterIndex - 1]
                        CarouselSideCard(
                            slot = prevSlot,
                            onClick = { carouselIndex = safeCenterIndex - 1 },
                            modifier = Modifier.weight(0.85f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    } else {
                        Spacer(modifier = Modifier.weight(0.2f))
                    }

                    // Center Enlarged Card with Good Lighting
                    CarouselCenterCard(
                        preset = preset,
                        slot = currentSlot,
                        onRename = { renamingSlotTarget = currentSlot },
                        onFastSetup = {
                            val template = repository.createFreeFirePreset(preset.info.presetId, currentSlot.id)
                            currentSlot.isConfigured = true
                            currentSlot.clicks.clear()
                            currentSlot.clicks.addAll(template.clicks)
                            currentSlot.joystick = template.joystick.copy()
                            currentSlot.camera = template.camera.copy()
                            repository.savePreset(preset)
                            refreshAll()
                            Toast.makeText(this@MainActivity, "⚡ Fast Setup Applied to ${currentSlot.name}!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1.5f)
                    )

                    // Right Adjacent Card (if exists)
                    if (safeCenterIndex < preset.slots.size - 1) {
                        Spacer(modifier = Modifier.width(8.dp))
                        val nextSlot = preset.slots[safeCenterIndex + 1]
                        CarouselSideCard(
                            slot = nextSlot,
                            onClick = { carouselIndex = safeCenterIndex + 1 },
                            modifier = Modifier.weight(0.85f)
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(0.2f))
                    }
                }

                // Bottom Up-Arrow Indicator (▲) with Cyan Lighting
                Text(
                    text = "▲",
                    color = CyberCyan,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Carousel Stepper Controls: [ ‹ Prev ]  Slot X of N  [ Next › ]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { if (safeCenterIndex > 0) carouselIndex = safeCenterIndex - 1 },
                        enabled = safeCenterIndex > 0
                    ) {
                        Text("‹", color = if (safeCenterIndex > 0) CyberCyan else TextMuted, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }

                    Text(
                        text = "Slot ${safeCenterIndex + 1} of ${preset.slots.size}",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    IconButton(
                        onClick = { if (safeCenterIndex < preset.slots.size - 1) carouselIndex = safeCenterIndex + 1 },
                        enabled = safeCenterIndex < preset.slots.size - 1
                    ) {
                        Text("›", color = if (safeCenterIndex < preset.slots.size - 1) CyberCyan else TextMuted, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action Button: "Shift Active Keybinding to Selected"
            val safeCenter = carouselIndex.coerceIn(0, preset.slots.size - 1)
            val selectedSlot = preset.slots[safeCenter]
            val isAlreadyActive = preset.info.bindingId == selectedSlot.id

            Button(
                onClick = {
                    preset.info.bindingId = selectedSlot.id
                    repository.setActivePreset(preset, selectedSlot.id)
                    refreshAll()
                    if (isOverlayRunning) {
                        startService(Intent(this@MainActivity, OverlayService::class.java).apply {
                            action = OverlayService.ACTION_SWITCH_PRESET
                            putExtra(OverlayService.EXTRA_PRESET_ID, preset.info.presetId)
                            putExtra(OverlayService.EXTRA_BINDING_ID, selectedSlot.id)
                        })
                    }
                    wsClient.sendProfileActivated(
                        presetId = preset.info.presetId,
                        bindingId = selectedSlot.id,
                        presetName = preset.info.name,
                        bindingName = selectedSlot.name,
                        packageName = preset.info.packageName,
                        controllerType = preset.info.controllerType,
                        via = preset.info.via,
                        path = "/sdcard/VbridgeBind/preset${preset.info.presetId}/keybinding${selectedSlot.id}/",
                        profileJson = preset.toFullProfileJson()
                    )
                    Toast.makeText(this@MainActivity, "Shifted active to: ${selectedSlot.name}", Toast.LENGTH_SHORT).show()
                },
                enabled = !isAlreadyActive,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isAlreadyActive) Color(0xFF162A2B) else CyberCyan,
                    disabledContainerColor = Color(0xFF13202C)
                ),
                border = BorderStroke(1.dp, if (isAlreadyActive) NeonEmerald.copy(alpha = 0.5f) else CyberCyan)
            ) {
                if (isAlreadyActive) {
                    Text("✓ ACTIVE KEYBINDING (${selectedSlot.name})", color = NeonEmerald, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                } else {
                    Text("⚡ Shift Active Keybinding to ${selectedSlot.name}", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }

        // Rename Slot Dialog
        renamingSlotTarget?.let { targetSlot ->
            var slotNameText by remember { mutableStateOf(targetSlot.name) }
            AlertDialog(
                onDismissRequest = { renamingSlotTarget = null },
                containerColor = Color(0xEE121A26),
                title = { Text("Rename Keybinding Slot", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) },
                text = {
                    OutlinedTextField(
                        value = slotNameText,
                        onValueChange = { slotNameText = it },
                        label = { Text("Slot Name") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CyberCyan, unfocusedBorderColor = Color(0x33FFFFFF))
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val trimmed = slotNameText.trim()
                            if (trimmed.isNotEmpty()) {
                                targetSlot.name = trimmed
                                repository.savePreset(preset)
                                refreshAll()
                            }
                            renamingSlotTarget = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyberCyan)
                    ) {
                        Text("Save", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { renamingSlotTarget = null }) { Text("Cancel", color = TextMuted) }
                }
            )
        }
    }

    @Composable
    private fun CarouselCenterCard(
        preset: Preset,
        slot: KeybindingSlot,
        onRename: () -> Unit,
        onFastSetup: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        val isActive = preset.info.bindingId == slot.id

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xEE141F30),
            border = BorderStroke(2.dp, if (isActive) CyberCyan else Color(0x9900E5FF)),
            shadowElevation = 8.dp,
            modifier = modifier
                .fillMaxHeight()
                .graphicsLayer {
                    scaleX = 1.05f
                    scaleY = 1.05f
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Row: Slot Name, Edit Button, Active Indicator
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = slot.name,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        IconButton(onClick = onRename, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Edit, contentDescription = "Rename", tint = TextMuted, modifier = Modifier.size(13.dp))
                        }
                    }

                    if (isActive) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF003844))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("ACTIVE", color = CyberCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Middle: Features and status
                Column {
                    Text(
                        text = if (slot.isConfigured) "• ${slot.clicks.size} Keys mapped" else "• Blank Keybinding",
                        color = if (slot.isConfigured) Color(0xFFE2E8F0) else TextMuted,
                        fontSize = 11.sp
                    )
                    Text(
                        text = "• Joystick: ${if (slot.joystick.enabled) "WASD On" else "Off"}",
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                    Text(
                        text = "• Aim Pivot: ${if (slot.camera.enabled) "Mouse On" else "Off"}",
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                }

                // Bottom: Action or status
                if (!slot.isConfigured) {
                    Button(
                        onClick = onFastSetup,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0x3300E5FF)),
                        border = BorderStroke(1.dp, CyberCyan),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("⚡ Fast Setup", color = CyberCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Text(
                        text = "Lock: ${if (slot.isLocked) "Pass-Through" else "Edit Mode"}",
                        color = if (slot.isLocked) NeonEmerald else CyberCyan,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }

    @Composable
    private fun CarouselSideCard(
        slot: KeybindingSlot,
        onClick: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(12.dp),
            color = Color(0x66101724),
            border = BorderStroke(1.dp, Color(0x22FFFFFF)),
            modifier = modifier
                .fillMaxHeight(0.88f)
                .graphicsLayer {
                    alpha = 0.55f
                    scaleX = 0.90f
                    scaleY = 0.90f
                }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = slot.name,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (slot.isConfigured) "${slot.clicks.size} Keys" else "Blank",
                    color = TextMuted,
                    fontSize = 10.sp
                )
            }
        }
    }

    @Composable
    private fun HostConfigDialog(onDismiss: () -> Unit) {
        var hostText by remember { mutableStateOf(wsClient.host) }
        var portText by remember { mutableStateOf(wsClient.port.toString()) }

        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Color(0xEE121A26),
            title = {
                Text("Bridge Connection", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Text("Persistent WebSocket bridge to Windows Trajectory Engine", color = TextMuted, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = hostText,
                        onValueChange = { hostText = it },
                        label = { Text("Host IP") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyberCyan,
                            unfocusedBorderColor = Color(0x33FFFFFF)
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it },
                        label = { Text("Port") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyberCyan,
                            unfocusedBorderColor = Color(0x33FFFFFF)
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        wsClient.host = hostText
                        wsClient.port = portText.toIntOrNull() ?: WebSocketBridgeClient.DEFAULT_PORT
                        wsClient.connect()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberCyan)
                ) {
                    Text("Connect", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = TextMuted)
                }
            }
        )
    }

    @Composable
    private fun AppSelectionDialog(
        initialSelected: List<String>,
        onDismiss: () -> Unit,
        onConfirm: (List<String>) -> Unit
    ) {
        val context = LocalContext.current
        var searchQuery by remember { mutableStateOf("") }
        val selectedSet = remember { mutableStateListOf<String>().apply { addAll(initialSelected) } }

        var allApps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
        var isLoading by remember { mutableStateOf(true) }

        LaunchedEffect(Unit) {
            allApps = InstalledAppHelper.getInstalledApps(context)
            isLoading = false
        }

        val filteredApps = remember(searchQuery, allApps) {
            if (searchQuery.isBlank()) allApps
            else allApps.filter {
                it.appName.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
            }
        }

        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Color(0xEE121A26),
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Select Target Apps", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text("${selectedSet.size} selected", color = CyberCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search by game or package...", color = TextMuted, fontSize = 12.sp) },
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, tint = CyberCyan, modifier = Modifier.size(18.dp))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyberCyan,
                            unfocusedBorderColor = Color(0x33FFFFFF)
                        )
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    if (isLoading) {
                        Box(modifier = Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = CyberCyan, modifier = Modifier.size(32.dp))
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(filteredApps, key = { it.packageName }) { app ->
                                val isSelected = selectedSet.contains(app.packageName)
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSelected) Color(0x4D00E5FF) else Color(0x331E293B),
                                    border = BorderStroke(1.dp, if (isSelected) CyberCyan else Color(0x1AFFFFFF)),
                                    onClick = {
                                        if (isSelected) selectedSet.remove(app.packageName)
                                        else selectedSet.add(app.packageName)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val iconBmp = remember(app.packageName) {
                                            InstalledAppHelper.drawableToImageBitmap(app.icon, 96, 96)
                                        }
                                        Box(
                                            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF0F141C)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (iconBmp != null) {
                                                Image(bitmap = iconBmp, contentDescription = app.appName, modifier = Modifier.fillMaxSize())
                                            } else {
                                                Text("🎮", fontSize = 16.sp)
                                            }
                                        }

                                        Spacer(modifier = Modifier.width(10.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(app.appName, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                                if (app.isGame) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Box(
                                                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF003844)).padding(horizontal = 4.dp, vertical = 1.dp)
                                                    ) {
                                                        Text("GAME", color = CyberCyan, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
                                            Text(app.packageName, color = TextMuted, fontSize = 10.sp, maxLines = 1)
                                        }

                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = {
                                                if (it) selectedSet.add(app.packageName)
                                                else selectedSet.remove(app.packageName)
                                            },
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = CyberCyan,
                                                checkmarkColor = Color.Black
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onConfirm(selectedSet.toList())
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberCyan)
                ) {
                    Text("Done (${selectedSet.size})", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = TextMuted)
                }
            }
        )
    }

    @Composable
    private fun PresetFormDialog(
        initialPreset: Preset?,
        onDismiss: () -> Unit,
        onSaved: () -> Unit
    ) {
        val isEditing = initialPreset != null
        var name by remember { mutableStateOf(initialPreset?.info?.name ?: "Custom Layout") }
        var targetPackages by remember {
            mutableStateOf(
                if (initialPreset != null) initialPreset.info.targetPackages.ifEmpty { listOf(initialPreset.info.packageName) }
                else listOf("com.dts.freefiremax")
            )
        }
        var controllerType by remember { mutableStateOf(initialPreset?.info?.controllerType ?: "Python") }
        var via by remember { mutableStateOf(initialPreset?.info?.via ?: "ADB+Root") }
        var showAppSelectDialog by remember { mutableStateOf(false) }

        val supportedControllers = listOf("Python", "C++ Native", "Direct Kernel")
        val supportedVias = listOf("ADB+Root", "USB OTG", "Bluetooth", "TCP Bridge")

        if (showAppSelectDialog) {
            AppSelectionDialog(
                initialSelected = targetPackages,
                onDismiss = { showAppSelectDialog = false },
                onConfirm = { selected ->
                    targetPackages = selected.ifEmpty { listOf("com.dts.freefiremax") }
                    showAppSelectDialog = false
                }
            )
        }

        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Color(0xEE121A26),
            title = {
                Text(if (isEditing) "Edit Preset" else "Create Preset", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Preset Name") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyberCyan,
                            unfocusedBorderColor = Color(0x33FFFFFF)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Target Games / Apps Section (Selectable, NOT enterable!)
                    Text("Target Games & Apps", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(6.dp))

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0x66182232),
                        border = BorderStroke(1.dp, Color(0x26FFFFFF)),
                        onClick = { showAppSelectDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                AppIconStack(packages = targetPackages)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "${targetPackages.size} Selected",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = targetPackages.joinToString(", ").take(35) + if (targetPackages.joinToString(", ").length > 35) "..." else "",
                                        color = TextMuted,
                                        fontSize = 10.sp,
                                        maxLines = 1
                                    )
                                }
                            }

                            Button(
                                onClick = { showAppSelectDialog = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                shape = RoundedCornerShape(6.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0x3300E5FF)),
                                border = BorderStroke(1.dp, CyberCyan)
                            ) {
                                Text("Select", color = CyberCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Controller Type (Strictly Selectable, NOT editable!)
                    Text("Controller Type", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (ct in supportedControllers) {
                            val isSel = controllerType == ct
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSel) Color(0x3300E5FF) else Color(0xFF1E293B),
                                border = BorderStroke(1.dp, if (isSel) CyberCyan else Color(0x26FFFFFF)),
                                onClick = { controllerType = ct },
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                ) {
                                    Text(
                                        text = ct,
                                        color = if (isSel) CyberCyan else Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Via Method (Strictly Selectable, NOT editable!)
                    Text("Connection Via", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (v in supportedVias) {
                            val isSel = via == v
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSel) Color(0x3300E5FF) else Color(0xFF1E293B),
                                border = BorderStroke(1.dp, if (isSel) CyberCyan else Color(0x26FFFFFF)),
                                onClick = { via = v },
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                ) {
                                    Text(
                                        text = v,
                                        color = if (isSel) CyberCyan else Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val pName = name.trim().ifEmpty { "Preset" }
                        val pkgs = targetPackages.ifEmpty { listOf("com.dts.freefiremax") }
                        val mainPkg = pkgs.first()

                        if (isEditing && initialPreset != null) {
                            initialPreset.info.name = pName
                            initialPreset.info.packageName = mainPkg
                            initialPreset.info.targetPackages.clear()
                            initialPreset.info.targetPackages.addAll(pkgs)
                            initialPreset.info.controllerType = controllerType
                            initialPreset.info.via = via
                            repository.savePreset(initialPreset)
                        } else {
                            val nextId = repository.getNextPresetId()
                            val newInfo = PresetInfo(
                                presetId = nextId,
                                bindingId = 1,
                                name = pName,
                                packageName = mainPkg,
                                appName = if (mainPkg.contains("freefire")) "Free Fire MAX" else "Battlegrounds",
                                targetPackages = pkgs.toMutableList(),
                                controllerType = controllerType,
                                via = via
                            )
                            val newPreset = repository.createFreeFirePreset(nextId, 1).copy(
                                newPresetId = nextId,
                                newName = pName
                            ).apply {
                                info = newInfo
                            }
                            repository.savePreset(newPreset)
                        }
                        onSaved()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberCyan)
                ) {
                    Text("Confirm", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = TextMuted)
                }
            }
        )
    }
}
