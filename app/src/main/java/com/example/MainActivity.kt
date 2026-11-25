package com.example

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.PhonelinkErase
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.theme.KavachAmber
import com.example.ui.theme.KavachCrimson
import com.example.ui.theme.KavachCyan
import com.example.ui.theme.KavachEmerald
import com.example.ui.theme.KavachTheme

/**
 * Snapshot of the three mandatory zero-trust Android system privileges.
 */
data class SystemPermissionsSnapshot(
    val accessibilityGranted: Boolean = false,
    val usageStatsGranted: Boolean = false,
    val drawOverlaysGranted: Boolean = false
) {
    val grantedCount: Int
        get() = listOf(accessibilityGranted, usageStatsGranted, drawOverlaysGranted).count { it }

    val allGranted: Boolean
        get() = accessibilityGranted && usageStatsGranted && drawOverlaysGranted
}

/**
 * MainActivity — Zero-Trust Security Command Center for Kavach-UPI.
 *
 * Responsibilities:
 * 1. Displays real-time edge protection status (`ARMED`, `SETUP REQUIRED`, or `THREAT INTERCEPTED`)
 *    synced via [KavachSecurityRegistry.runtimeState].
 * 2. Evaluates all three OS security permissions on every `ON_RESUME` lifecycle transition:
 *    - `AccessibilityService` (`Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`)
 *    - `Usage Access` (`AppOpsManager.OPSTR_GET_USAGE_STATS`)
 *    - `Draw Over Other Apps` (`Settings.canDrawOverlays`)
 * 3. Launches deep-linked system Settings screens to grant missing permissions in one tap.
 * 4. Hosts a standalone [OverlayManager] + [BilingualTTSManager] fallback so the user can test
 *    the real `WindowManager` touch-blocking glassmorphic shield and bilingual English/Hindi
 *    voice alerts via "Live Shield Drill" even before opening an external UPI app.
 */
class MainActivity : ComponentActivity() {

    private lateinit var localTtsManager: BilingualTTSManager
    private lateinit var localOverlayManager: OverlayManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        localTtsManager = BilingualTTSManager(applicationContext)
        localOverlayManager = OverlayManager(
            baseContext = applicationContext,
            onMuteVoiceRequested = {
                localTtsManager.stopAlerts()
            },
            onExitPaymentAppRequested = {
                stopLocalSafetyDrill()
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(homeIntent)
            },
            onEndSafetyDrillRequested = {
                stopLocalSafetyDrill()
            }
        )

        setContent {
            KavachTheme(darkTheme = true) {
                KavachDashboardApp(
                    onOpenAccessibilitySettings = ::openAccessibilitySettings,
                    onOpenUsageAccessSettings = ::openUsageAccessSettings,
                    onOpenOverlaySettings = ::openOverlaySettings,
                    onTriggerLiveDrill = ::triggerLiveSafetyDrill,
                    onTestBilingualVoice = { localTtsManager.playSingleDiagnosticSample() }
                )
            }
        }
    }

    override fun onDestroy() {
        localOverlayManager.dismissOverlay()
        localTtsManager.shutdown()
        super.onDestroy()
    }

    /**
     * Launches Android Accessibility Settings so the user can toggle "Kavach-UPI Zero-Trust Shield".
     */
    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (t: Throwable) {
            Toast.makeText(this, "Unable to open Accessibility Settings", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Launches Android Usage Access Settings scoped to this application's package URI when supported.
     */
    private fun openUsageAccessSettings() {
        try {
            val scopedIntent = Intent(
                Settings.ACTION_USAGE_ACCESS_SETTINGS,
                Uri.parse("package:$packageName")
            )
            startActivity(scopedIntent)
        } catch (_: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (_: Throwable) {
                Toast.makeText(this, "Unable to open Usage Access Settings", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Launches "Display over other apps" (`ACTION_MANAGE_OVERLAY_PERMISSION`) directly for Kavach-UPI.
     */
    private fun openOverlaySettings() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (_: Throwable) {
            Toast.makeText(this, "Unable to open Overlay Permission Settings", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Triggers a live hardware verification drill of the touch-blocking glassmorphic overlay and
     * bilingual English/Hindi TTS siren:
     * - Uses `KavachAccessibilityService` (`TYPE_ACCESSIBILITY_OVERLAY`) if already bound.
     * - Otherwise mounts directly via `localOverlayManager` (`TYPE_APPLICATION_OVERLAY`) if
     *   `Settings.canDrawOverlays` is granted.
     */
    private fun triggerLiveSafetyDrill() {
        val handledByAccessibility = KavachAccessibilityService.triggerSafetyDrillFromUi(enable = true)
        if (handledByAccessibility) {
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                getString(R.string.drill_overlay_needed_hint),
                Toast.LENGTH_LONG
            ).show()
            openOverlaySettings()
            return
        }

        val mounted = localOverlayManager.showOverlay(
            accessibilityContext = null,
            threatSource = "Simulated AnyDesk + MediaProjection (Safety Drill)",
            protectedTarget = "Google Pay UPI PIN Surface (Drill)",
            isDrill = true
        )
        if (mounted) {
            localTtsManager.startContinuousAlert("AnyDesk Remote Control Drill")
            KavachSecurityRegistry.updateState {
                it.copy(
                    isThreatActive = true,
                    isSafetyDrillActive = true,
                    detectedThreatSource = "Simulated AnyDesk + MediaProjection (Safety Drill)",
                    detectedPaymentTarget = "Google Pay UPI PIN Surface (Drill)",
                    overlayMounted = true
                )
            }
        }
    }

    private fun stopLocalSafetyDrill() {
        KavachAccessibilityService.triggerSafetyDrillFromUi(enable = false)
        localTtsManager.stopAlerts()
        localOverlayManager.dismissOverlay()
        KavachSecurityRegistry.updateState {
            it.copy(
                isThreatActive = false,
                isSafetyDrillActive = false,
                detectedThreatSource = null,
                overlayMounted = false
            )
        }
    }

    companion object {
        /**
         * Queries OS settings & AppOps to determine current grant states for all 3 security pillars.
         */
        fun checkSystemPermissions(context: Context): SystemPermissionsSnapshot {
            val accessibilityEnabled = isAccessibilityServiceEnabled(
                context,
                KavachAccessibilityService::class.java
            )
            val usageStatsEnabled = hasUsageStatsPermission(context)
            val overlayEnabled = Settings.canDrawOverlays(context)

            return SystemPermissionsSnapshot(
                accessibilityGranted = accessibilityEnabled,
                usageStatsGranted = usageStatsEnabled,
                drawOverlaysGranted = overlayEnabled
            )
        }

        fun isAccessibilityServiceEnabled(
            context: Context,
            serviceClass: Class<*>
        ): Boolean {
            val expectedComponent = ComponentName(context, serviceClass)
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServicesSetting)
            while (colonSplitter.hasNext()) {
                val componentNameString = colonSplitter.next()
                val enabledService = ComponentName.unflattenFromString(componentNameString)
                if (enabledService != null && enabledService == expectedComponent) {
                    return true
                }
            }
            return false
        }

        fun hasUsageStatsPermission(context: Context): Boolean {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}

@Composable
fun KavachDashboardApp(
    onOpenAccessibilitySettings: () -> Unit,
    onOpenUsageAccessSettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onTriggerLiveDrill: () -> Unit,
    onTestBilingualVoice: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val runtimeState by KavachSecurityRegistry.runtimeState.collectAsStateWithLifecycle()
    val auditEvents by KavachSecurityRegistry.auditLog.collectAsStateWithLifecycle()

    var permissions by remember {
        mutableStateOf(MainActivity.checkSystemPermissions(context))
    }

    // Re-evaluate system privileges automatically whenever returning from Android Settings
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissions = MainActivity.checkSystemPermissions(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    // Custom tab state back navigation handler per Android Navigation guidelines
    BackHandler(enabled = selectedTab != 0) {
        selectedTab = 0
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val isWideScreen = maxWidth >= 600.dp

        if (isWideScreen) {
            // Canonical Tablet / Foldable NavigationRail Layout
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxHeight()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                ) {
                    Spacer(Modifier.height(16.dp))
                    NavigationRailItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = {
                            Icon(
                                imageVector = if (selectedTab == 0) Icons.Filled.Security else Icons.Outlined.Shield,
                                contentDescription = stringResource(R.string.tab_shield)
                            )
                        },
                        label = { Text(stringResource(R.string.tab_shield)) },
                        modifier = Modifier.testTag("nav_tab_command")
                    )
                    NavigationRailItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.AccountBalance,
                                contentDescription = stringResource(R.string.tab_targets)
                            )
                        },
                        label = { Text(stringResource(R.string.tab_targets)) },
                        modifier = Modifier.testTag("nav_tab_targets")
                    )
                    NavigationRailItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.Terminal,
                                contentDescription = stringResource(R.string.tab_telemetry)
                            )
                        },
                        label = { Text(stringResource(R.string.tab_telemetry)) },
                        modifier = Modifier.testTag("nav_tab_audit")
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    KavachTabSwitcher(
                        selectedTab = selectedTab,
                        permissions = permissions,
                        runtimeState = runtimeState,
                        auditEvents = auditEvents,
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp),
                        onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                        onOpenUsageAccessSettings = onOpenUsageAccessSettings,
                        onOpenOverlaySettings = onOpenOverlaySettings,
                        onTriggerLiveDrill = onTriggerLiveDrill,
                        onTestBilingualVoice = onTestBilingualVoice
                    )
                }
            }
        } else {
            // Mobile-First Edge-to-Edge Scaffold with Material 3 NavigationBar
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets.statusBars,
                bottomBar = {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 8.dp,
                        windowInsets = WindowInsets.navigationBars
                    ) {
                        NavigationBarItem(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            icon = {
                                Icon(
                                    imageVector = if (selectedTab == 0) Icons.Filled.Security else Icons.Outlined.Shield,
                                    contentDescription = stringResource(R.string.tab_shield)
                                )
                            },
                            label = { Text(stringResource(R.string.tab_shield)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                indicatorColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.testTag("nav_tab_command")
                        )
                        NavigationBarItem(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            icon = {
                                Icon(
                                    imageVector = Icons.Outlined.AccountBalance,
                                    contentDescription = stringResource(R.string.tab_targets)
                                )
                            },
                            label = { Text(stringResource(R.string.tab_targets)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                indicatorColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.testTag("nav_tab_targets")
                        )
                        NavigationBarItem(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            icon = {
                                Icon(
                                    imageVector = Icons.Outlined.Terminal,
                                    contentDescription = stringResource(R.string.tab_telemetry)
                                )
                            },
                            label = { Text(stringResource(R.string.tab_telemetry)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                indicatorColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.testTag("nav_tab_audit")
                        )
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.TopCenter
                ) {
                    KavachTabSwitcher(
                        selectedTab = selectedTab,
                        permissions = permissions,
                        runtimeState = runtimeState,
                        auditEvents = auditEvents,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                        onOpenUsageAccessSettings = onOpenUsageAccessSettings,
                        onOpenOverlaySettings = onOpenOverlaySettings,
                        onTriggerLiveDrill = onTriggerLiveDrill,
                        onTestBilingualVoice = onTestBilingualVoice
                    )
                }
            }
        }
    }
}

@Composable
private fun KavachTabSwitcher(
    selectedTab: Int,
    permissions: SystemPermissionsSnapshot,
    runtimeState: KavachRuntimeState,
    auditEvents: List<SecurityAuditEvent>,
    contentPadding: PaddingValues,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenUsageAccessSettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onTriggerLiveDrill: () -> Unit,
    onTestBilingualVoice: () -> Unit
) {
    when (selectedTab) {
        0 -> CommandCenterScreen(
            permissions = permissions,
            runtimeState = runtimeState,
            contentPadding = contentPadding,
            onOpenAccessibilitySettings = onOpenAccessibilitySettings,
            onOpenUsageAccessSettings = onOpenUsageAccessSettings,
            onOpenOverlaySettings = onOpenOverlaySettings,
            onTriggerLiveDrill = onTriggerLiveDrill,
            onTestBilingualVoice = onTestBilingualVoice
        )

        1 -> ProtectedTargetsScreen(
            contentPadding = contentPadding
        )

        else -> AuditTelemetryScreen(
            auditEvents = auditEvents,
            contentPadding = contentPadding
        )
    }
}

@Composable
private fun CommandCenterScreen(
    permissions: SystemPermissionsSnapshot,
    runtimeState: KavachRuntimeState,
    contentPadding: PaddingValues,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenUsageAccessSettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onTriggerLiveDrill: () -> Unit,
    onTestBilingualVoice: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp)
            .testTag("command_center_list"),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Top Brand & Offline Autonomy Pill Header
        item {
            TopBrandHeader()
        }

        // 2. Hero Radar & Protection Status Card
        item {
            ProtectionStatusHeroCard(
                permissions = permissions,
                runtimeState = runtimeState
            )
        }

        // 3. Mandatory System Permissions Checklist
        item {
            Text(
                text = "${stringResource(R.string.section_permissions_title)} (${permissions.grantedCount}/3)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        item {
            PermissionStepCard(
                title = stringResource(R.string.perm_accessibility_title),
                description = stringResource(R.string.perm_accessibility_desc),
                isGranted = permissions.accessibilityGranted,
                icon = Icons.Filled.Visibility,
                buttonTestTag = "btn_perm_accessibility",
                onClick = onOpenAccessibilitySettings
            )
        }

        item {
            PermissionStepCard(
                title = stringResource(R.string.perm_usage_title),
                description = stringResource(R.string.perm_usage_desc),
                isGranted = permissions.usageStatsGranted,
                icon = Icons.Filled.QueryStats,
                buttonTestTag = "btn_perm_usage_stats",
                onClick = onOpenUsageAccessSettings
            )
        }

        item {
            PermissionStepCard(
                title = stringResource(R.string.perm_overlay_title),
                description = stringResource(R.string.perm_overlay_desc),
                isGranted = permissions.drawOverlaysGranted,
                icon = Icons.Filled.Layers,
                buttonTestTag = "btn_perm_overlay",
                onClick = onOpenOverlaySettings
            )
        }

        // 4. Dual-Gear & DFS Telemetry Matrix
        item {
            Text(
                text = stringResource(R.string.section_engine_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        item {
            EngineTelemetryGrid(runtimeState = runtimeState)
        }

        // 5. Live Shield & Bilingual Voice Drill Card
        item {
            LiveSafetyDrillCard(
                canMountOverlay = permissions.drawOverlaysGranted || permissions.accessibilityGranted,
                onTriggerLiveDrill = onTriggerLiveDrill,
                onTestBilingualVoice = onTestBilingualVoice
            )
        }

        item {
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun TopBrandHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = stringResource(R.string.brand_subtitle),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary
            )
        }

        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
            shape = RoundedCornerShape(50),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.WifiOff,
                    contentDescription = stringResource(R.string.badge_offline),
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = "OFFLINE EDGE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
private fun ProtectionStatusHeroCard(
    permissions: SystemPermissionsSnapshot,
    runtimeState: KavachRuntimeState
) {
    val isThreat = runtimeState.isThreatActive
    val isFullyArmed = permissions.allGranted

    val accentColor by animateColorAsState(
        targetValue = when {
            isThreat -> KavachCrimson
            isFullyArmed -> KavachEmerald
            else -> KavachAmber
        },
        animationSpec = tween(300),
        label = "shieldAccent"
    )

    val pulseTransition = rememberInfiniteTransition(label = "radarPulse")
    val pulseRadiusFactor by pulseTransition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isThreat) 700 else 1800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("hero_status_card"),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        border = BorderStroke(1.5.dp, accentColor.copy(alpha = 0.55f))
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // Generated cyber-shield hero banner blended subtly as atmospheric backdrop
            Image(
                painter = painterResource(id = R.drawable.img_hero_shield_1793125260973),
                contentDescription = stringResource(R.string.hero_banner_content_desc),
                contentScale = ContentScale.Crop,
                alpha = 0.22f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(215.dp)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
                                MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f),
                                MaterialTheme.colorScheme.surfaceContainer
                            )
                        )
                    )
                    .padding(20.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Animated Radar Shield Emblem using DrawScope dimensions dynamically
                        Box(
                            modifier = Modifier.size(72.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .semantics {
                                        contentDescription = "Active Radar Pulse Ring"
                                    }
                            ) {
                                val minDim = size.minDimension
                                val centerOffset = Offset(size.width / 2f, size.height / 2f)
                                drawCircle(
                                    color = accentColor.copy(alpha = 0.16f),
                                    radius = (minDim / 2f) * pulseRadiusFactor,
                                    center = centerOffset
                                )
                                drawCircle(
                                    color = accentColor.copy(alpha = 0.45f),
                                    radius = (minDim / 2f) * 0.78f,
                                    center = centerOffset,
                                    style = Stroke(width = 2.dp.toPx())
                                )
                            }

                            Icon(
                                imageVector = when {
                                    isThreat -> Icons.Filled.GppBad
                                    isFullyArmed -> Icons.Filled.GppGood
                                    else -> Icons.Filled.GppMaybe
                                },
                                contentDescription = stringResource(R.string.app_name),
                                tint = accentColor,
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = when {
                                    isThreat -> stringResource(R.string.status_threat_title)
                                    isFullyArmed -> stringResource(R.string.status_armed_title)
                                    else -> stringResource(R.string.status_partial_title)
                                },
                                style = MaterialTheme.typography.headlineSmall,
                                color = accentColor
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = when {
                                    isThreat -> runtimeState.detectedThreatSource
                                        ?: stringResource(R.string.status_threat_desc)
                                    isFullyArmed -> stringResource(R.string.status_armed_desc)
                                    else -> stringResource(R.string.status_partial_desc)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))

                    // Live Foreground Window & Service Heartbeat Strip
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "ACTIVE WINDOW MONITOR",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = runtimeState.activeForegroundPackage,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                color = accentColor.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = if (runtimeState.isServiceConnected) {
                                        "SERVICE BOUND"
                                    } else {
                                        "SERVICE STANDBY"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = accentColor,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }

                            if (runtimeState.totalThreatInterceptions > 0) {
                                Surface(
                                    color = KavachCrimson.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, KavachCrimson.copy(alpha = 0.45f))
                                ) {
                                    Text(
                                        text = "BLOCKED: ${runtimeState.totalThreatInterceptions}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = KavachCrimson,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
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
private fun PermissionStepCard(
    title: String,
    description: String,
    isGranted: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    buttonTestTag: String,
    onClick: () -> Unit
) {
    val badgeColor = if (isGranted) KavachEmerald else KavachAmber

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (isGranted) {
                KavachEmerald.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.outline
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = badgeColor.copy(alpha = 0.14f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isGranted) Icons.Filled.CheckCircle else icon,
                        contentDescription = title,
                        tint = badgeColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isGranted) {
                OutlinedButton(
                    onClick = onClick,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    border = BorderStroke(1.dp, KavachEmerald.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag(buttonTestTag)
                ) {
                    Text(
                        text = stringResource(R.string.btn_granted),
                        style = MaterialTheme.typography.labelLarge,
                        color = KavachEmerald
                    )
                }
            } else {
                Button(
                    onClick = onClick,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag(buttonTestTag)
                ) {
                    Text(
                        text = stringResource(R.string.btn_grant),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun EngineTelemetryGrid(runtimeState: KavachRuntimeState) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TelemetryMetricTile(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.metric_gear_label),
                value = if (runtimeState.trackingGear == TrackingGear.HIGH_GEAR_COMBAT) {
                    stringResource(R.string.metric_gear_high)
                } else {
                    stringResource(R.string.metric_gear_low)
                },
                accent = if (runtimeState.trackingGear == TrackingGear.HIGH_GEAR_COMBAT) {
                    KavachCrimson
                } else {
                    KavachCyan
                },
                icon = Icons.Outlined.Speed
            )
            TelemetryMetricTile(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.metric_dfs_label),
                value = if (runtimeState.lastDfsNodesInspected > 0) {
                    "${runtimeState.lastDfsNodesInspected} nodes (d≤${runtimeState.lastDfsMaxDepthReached}/15)"
                } else {
                    stringResource(R.string.metric_dfs_value)
                },
                accent = KavachEmerald,
                icon = Icons.Outlined.Layers
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TelemetryMetricTile(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.metric_hysteresis_label),
                value = "${runtimeState.cleanHysteresisCount}/${KavachAccessibilityService.HYSTERESIS_CLEAN_CYCLES_REQUIRED} Clean Ticks",
                accent = KavachCyan,
                icon = Icons.Outlined.Policy
            )
            TelemetryMetricTile(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.metric_tts_label),
                value = if (runtimeState.ttsInitialized) {
                    stringResource(R.string.metric_tts_ready)
                } else {
                    stringResource(R.string.metric_tts_init)
                },
                accent = if (runtimeState.ttsInitialized) KavachEmerald else KavachAmber,
                icon = Icons.Filled.RecordVoiceOver
            )
        }
    }
}

@Composable
private fun TelemetryMetricTile(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    accent: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = accent,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LiveSafetyDrillCard(
    canMountOverlay: Boolean,
    onTriggerLiveDrill: () -> Unit,
    onTestBilingualVoice: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("safety_drill_card"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.drill_card_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.drill_card_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            AnimatedVisibility(visible = !canMountOverlay) {
                Text(
                    text = stringResource(R.string.drill_overlay_needed_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = KavachAmber
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onTriggerLiveDrill,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = KavachCrimson,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("btn_trigger_live_drill")
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.btn_run_drill),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                FilledTonalButton(
                    onClick = onTestBilingualVoice,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("btn_test_bilingual_voice")
                ) {
                    Icon(
                        imageVector = Icons.Filled.RecordVoiceOver,
                        contentDescription = stringResource(R.string.btn_test_voice),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "EN/HI",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun ProtectedTargetsScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val pm = context.packageManager

    val installedSet = remember(context) {
        val detected = mutableSetOf<String>()
        (KavachSecurityRegistry.PROTECTED_PAYMENT_TARGETS.map { it.packageName } +
            KavachSecurityRegistry.REMOTE_THREAT_SIGNATURES.map { it.packageName }).forEach { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                detected.add(pkg)
            } catch (_: Exception) {
                // Not installed on current device
            }
        }
        detected
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp)
            .testTag("protected_targets_list"),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.targets_upi_header),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.targets_upi_sub),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        items(
            items = KavachSecurityRegistry.PROTECTED_PAYMENT_TARGETS,
            key = { it.packageName }
        ) { target ->
            val isInstalled = installedSet.contains(target.packageName)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AccountBalance,
                        contentDescription = target.displayName,
                        tint = KavachEmerald,
                        modifier = Modifier.size(22.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = target.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = target.packageName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    StatusPill(
                        label = if (isInstalled) {
                            stringResource(R.string.tag_installed)
                        } else {
                            target.category
                        },
                        color = if (isInstalled) KavachEmerald else KavachCyan
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.targets_remote_header),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.targets_remote_sub),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        items(
            items = KavachSecurityRegistry.REMOTE_THREAT_SIGNATURES,
            key = { it.packageName }
        ) { sig ->
            val isInstalled = installedSet.contains(sig.packageName)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(
                    1.dp,
                    if (isInstalled) KavachCrimson.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline
                )
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PhonelinkErase,
                        contentDescription = sig.displayName,
                        tint = if (isInstalled) KavachCrimson else KavachAmber,
                        modifier = Modifier.size(22.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = sig.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${sig.riskVector} • ${sig.packageName}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    StatusPill(
                        label = if (isInstalled) "ON DEVICE" else stringResource(R.string.tag_monitored),
                        color = if (isInstalled) KavachCrimson else KavachAmber
                    )
                }
            }
        }
    }
}

@Composable
private fun AuditTelemetryScreen(
    auditEvents: List<SecurityAuditEvent>,
    contentPadding: PaddingValues
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 640.dp)
            .testTag("audit_log_list"),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.log_header),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "RAM-only volatile ring buffer (max 60 events)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                IconButton(
                    onClick = { KavachSecurityRegistry.clearAuditLog() },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("btn_clear_audit_log")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteSweep,
                        contentDescription = stringResource(R.string.log_clear),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (auditEvents.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Terminal,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = stringResource(R.string.log_empty_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.log_empty_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(
                items = auditEvents,
                key = { it.id }
            ) { entry ->
                val accent = when (entry.severity) {
                    AuditSeverity.CRITICAL -> KavachCrimson
                    AuditSeverity.WARNING -> KavachAmber
                    AuditSeverity.INFO -> KavachCyan
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StatusPill(label = entry.tag, color = accent)
                            Text(
                                text = entry.timestampFormatted,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = entry.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(label: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.4f))
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}
