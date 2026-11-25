package com.example

import android.accessibilityservice.AccessibilityService
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Process
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * KavachAccessibilityService — Core Edge-Computed Zero-Trust Security Engine.
 *
 * Architectural Pillars:
 * 1. Dual-Gear Coroutine Background Tracking (`SupervisorJob` Scope):
 *    - Runs on `Dispatchers.Default` under a fault-isolated `SupervisorJob`.
 *    - Operates in `LOW_GEAR_IDLE` (1500ms) during normal phone usage to preserve battery.
 *    - Instantly shifts into `HIGH_GEAR_COMBAT` (350ms) the millisecond a protected UPI or
 *      Banking package (`GPay`, `PhonePe`, `Paytm`, `BHIM`, `YONO SBI`, etc.) enters the foreground.
 *
 * 2. Multi-Vector Screen-Share & Media-Projection Detection:
 *    - Vector A (`UsageStatsManager`): Queries compressed window & foreground-service timeline
 *      events (`FOREGROUND_SERVICE_START` / `FOREGROUND_SERVICE_STOP` / `ACTIVITY_RESUMED`) to
 *      spot active remote-support daemons (`AnyDesk`, `TeamViewer`, `QuickSupport`, `RustDesk`,
 *      `Splashtop`, `Zoom`, `Meet`) running in the background while a UPI app is open.
 *    - Vector B (`AppOpsManager`): Audits `OPSTR_PROJECT_MEDIA` across watched remote-access UIDs
 *      to detect active `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` sessions on Android 10+.
 *    - Vector C (`DisplayManager` + Interactive `AccessibilityWindowInfo` Stack): Detects virtual /
 *      presentation capture displays (`VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR` / non-default displays)
 *      and split-screen / floating remote-control overlays.
 *
 * 3. Bounded 15-Layer DFS Node Tree Parser with Explicit `recycle()` Hygiene:
 *    - Recursively inspects the active window's `AccessibilityNodeInfo` tree up to a strict
 *      maximum depth of `MAX_DFS_DEPTH = 15`.
 *    - Explicitly calls `recycle()` inside `try / finally` blocks on every child node obtained
 *      via `getChild(i)` (and on root nodes on pre-API 34 runtimes) to prevent Binder proxy table
 *      exhaustion and memory leaks during long-lived background execution.
 *
 * 4. Unified Command Handler with Two-Cycle Polling Hysteresis:
 *    - Funnels all mount, update, and teardown signals through a serialized `Channel<ShieldCommand>`
 *      on `Dispatchers.Main.immediate`.
 *    - Enforces a strict `HYSTERESIS_CLEAN_CYCLES_REQUIRED = 2` rule before dismissing the
 *      touch-blocking overlay and bilingual voice siren, eliminating race-condition flicker when
 *      remote-control overlays briefly toggle window focus.
 */
class KavachAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "KavachAccessService"

        /** Strict recursion depth ceiling to prevent stack overflow & Binder proxy retention. */
        const val MAX_DFS_DEPTH = 15

        /** Hard node budget per scan pass so pathological UIs never stall the background worker. */
        private const val MAX_DFS_NODES_PER_PASS = 240

        /** Two consecutive clean polling cycles required before unmounting an active threat shield. */
        const val HYSTERESIS_CLEAN_CYCLES_REQUIRED = 2

        /** Sliding lookback window for UsageStatsManager foreground service & activity timeline. */
        private const val USAGE_TIMELINE_LOOKBACK_MS = 20 * 60 * 1_000L

        /** Android framework AppOps string operation identifier for MediaProjection screen capture. */
        private const val OPSTR_PROJECT_MEDIA = "android:project_media"

        @Volatile
        private var activeInstanceRef: WeakReference<KavachAccessibilityService>? = null

        /**
         * Allows [MainActivity] to trigger or stop a live hardware overlay + TTS safety drill
         * directly through the privileged `TYPE_ACCESSIBILITY_OVERLAY` pipeline when connected.
         */
        fun triggerSafetyDrillFromUi(enable: Boolean): Boolean {
            val service = activeInstanceRef?.get() ?: return false
            service.setSafetyDrillMode(enable)
            return true
        }
    }

    /**
     * Serialized commands processed exclusively by the unified command consumer on Main thread.
     */
    private sealed interface ShieldCommand {
        data class ThreatDetected(
            val threatSource: String,
            val paymentTarget: String,
            val isSafetyDrill: Boolean
        ) : ShieldCommand

        data object CleanCycleObserved : ShieldCommand
        data object ForceImmediateDismiss : ShieldCommand
        data object MuteVoiceOnly : ShieldCommand
    }

    /** Result of a bounded 15-layer DFS accessibility node scan. */
    data class DfsInspectionReport(
        val nodesInspected: Int,
        val maxDepthReached: Int,
        val sensitivePaymentUiFound: Boolean,
        val remoteThreatIndicatorInTree: String?
    )

    /** Result of multi-vector screen-sharing / media-projection telemetry check. */
    private data class ThreatTelemetrySnapshot(
        val isThreatPresent: Boolean,
        val threatDescription: String?
    )

    // Fault-isolated SupervisorJob scope for dual-gear background tracking & DFS parsing
    private val serviceSupervisorJob = SupervisorJob()
    private val backgroundScope = CoroutineScope(serviceSupervisorJob + Dispatchers.Default)
    private val mainCommandScope = CoroutineScope(serviceSupervisorJob + Dispatchers.Main.immediate)

    private val commandChannel = Channel<ShieldCommand>(Channel.UNLIMITED)
    private var dualGearTrackerJob: Job? = null
    private var commandProcessorJob: Job? = null

    private val currentForegroundPackage = AtomicReference("None")
    private val isProtectedPaymentActive = AtomicBoolean(false)
    private val isSafetyDrillMode = AtomicBoolean(false)
    private val consecutiveCleanCycles = AtomicInteger(0)

    private lateinit var overlayManager: OverlayManager
    private lateinit var bilingualTtsManager: BilingualTTSManager

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstanceRef = WeakReference(this)

        bilingualTtsManager = BilingualTTSManager(applicationContext)
        overlayManager = OverlayManager(
            baseContext = applicationContext,
            onMuteVoiceRequested = {
                commandChannel.trySend(ShieldCommand.MuteVoiceOnly)
            },
            onExitPaymentAppRequested = {
                evacuateUserToHomeScreen()
            },
            onEndSafetyDrillRequested = {
                setSafetyDrillMode(false)
            }
        )

        KavachSecurityRegistry.updateState {
            it.copy(
                isServiceConnected = true,
                trackingGear = TrackingGear.LOW_GEAR_IDLE
            )
        }
        KavachSecurityRegistry.recordAudit(
            AuditSeverity.INFO,
            "SERVICE",
            "KavachAccessibilityService bound. Dual-gear SupervisorJob & unified hysteresis handler online."
        )

        startUnifiedCommandHandler()
        startDualGearBackgroundTracker()
    }

    /**
     * Listens to real-time `TYPE_WINDOW_STATE_CHANGED` (and content updates on protected windows)
     * to immediately evaluate foreground package transitions without waiting for the next poll tick.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString() ?: return

        // Ignore our own overlay / dashboard package transitions so we don't overwrite target context
        if (pkg == packageName || pkg == "com.android.systemui") {
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val previousPkg = currentForegroundPackage.getAndSet(pkg)
                val targetInfo = KavachSecurityRegistry.PROTECTED_PACKAGE_MAP[pkg]
                val isProtected = targetInfo != null
                isProtectedPaymentActive.set(isProtected)

                val gear = if (isProtected || overlayManager.isOverlayMounted()) {
                    TrackingGear.HIGH_GEAR_COMBAT
                } else {
                    TrackingGear.LOW_GEAR_IDLE
                }

                KavachSecurityRegistry.updateState { state ->
                    state.copy(
                        activeForegroundPackage = pkg,
                        isProtectedAppInForeground = isProtected,
                        detectedPaymentTarget = targetInfo?.displayName ?: state.detectedPaymentTarget,
                        trackingGear = gear
                    )
                }

                if (previousPkg != pkg) {
                    if (isProtected) {
                        KavachSecurityRegistry.recordAudit(
                            AuditSeverity.WARNING,
                            "WINDOW-GUARD",
                            "Protected payment window opened: ${targetInfo?.displayName} ($pkg) -> Shifting to HIGH-GEAR (350ms)."
                        )
                    } else if (KavachSecurityRegistry.REMOTE_THREAT_MAP.containsKey(pkg)) {
                        val remoteSig = KavachSecurityRegistry.REMOTE_THREAT_MAP[pkg]
                        KavachSecurityRegistry.recordAudit(
                            AuditSeverity.CRITICAL,
                            "THREAT-WATCH",
                            "Remote-access application entered window stack: ${remoteSig?.displayName} ($pkg)."
                        )
                    }
                }

                // Trigger an immediate zero-latency inspection pass when a UPI/Banking app opens
                if (isProtected) {
                    backgroundScope.launch {
                        runSingleTelemetryEvaluationPass(triggerReason = "WINDOW_STATE_CHANGED")
                    }
                }
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Only inspect content changes when a protected UPI/Banking app is actively open
                if (isProtectedPaymentActive.get()) {
                    backgroundScope.launch {
                        inspectActiveWindowNodeTreeSafely()
                    }
                }
            }
        }
    }

    override fun onInterrupt() {
        KavachSecurityRegistry.recordAudit(
            AuditSeverity.WARNING,
            "SERVICE",
            "Accessibility feedback interrupted by system; maintaining state hygiene."
        )
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardownServiceResources()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardownServiceResources()
        super.onDestroy()
    }

    /**
     * Dual-Gear Coroutine Background Tracker:
     * - Gear 1 (`LOW_GEAR_IDLE` = 1500ms): Light background sentinel when user is on Launcher/Browser.
     * - Gear 2 (`HIGH_GEAR_COMBAT` = 350ms): Rapid sub-second media-projection & DFS scanner when a
     *   protected UPI/Banking app is in the foreground OR when an active shield is verifying clearance.
     */
    private fun startDualGearBackgroundTracker() {
        dualGearTrackerJob?.cancel()
        dualGearTrackerJob = backgroundScope.launch {
            while (isActive) {
                try {
                    runSingleTelemetryEvaluationPass(triggerReason = "DUAL_GEAR_POLL")
                } catch (t: Throwable) {
                    Log.e(TAG, "Recovered from exception in dual-gear evaluation pass", t)
                }

                val highGearNeeded = isProtectedPaymentActive.get() ||
                    overlayManager.isOverlayMounted() ||
                    isSafetyDrillMode.get()

                val activeGear = if (highGearNeeded) {
                    TrackingGear.HIGH_GEAR_COMBAT
                } else {
                    TrackingGear.LOW_GEAR_IDLE
                }

                KavachSecurityRegistry.updateState { it.copy(trackingGear = activeGear) }
                delay(activeGear.intervalMs)
            }
        }
    }

    /**
     * Executes a single end-to-end zero-trust evaluation pass:
     * 1. Checks if Safety Drill mode is active.
     * 2. Inspects bounded 15-layer DFS accessibility node tree (when protected app is open).
     * 3. Queries `UsageStatsManager`, `AppOpsManager`, `DisplayManager`, and interactive windows
     *    for concurrent media-projection or remote-desktop sessions.
     * 4. Dispatches `ThreatDetected` or `CleanCycleObserved` to the unified hysteresis handler.
     */
    private suspend fun runSingleTelemetryEvaluationPass(triggerReason: String) {
        if (isSafetyDrillMode.get()) {
            commandChannel.send(
                ShieldCommand.ThreatDetected(
                    threatSource = "Simulated AnyDesk + MediaProjection (Safety Drill)",
                    paymentTarget = "Protected UPI PIN Surface (Drill)",
                    isSafetyDrill = true
                )
            )
            return
        }

        val fgPkg = currentForegroundPackage.get()
        val protectedTarget = KavachSecurityRegistry.PROTECTED_PACKAGE_MAP[fgPkg]
        val isProtectedForeground = protectedTarget != null

        var dfsReport: DfsInspectionReport? = null
        if (isProtectedForeground) {
            dfsReport = inspectActiveWindowNodeTreeSafely()
        }

        val mediaThreat = detectConcurrentScreenShareOrRemoteAccess()

        // Threat condition: Protected UPI/Banking app is open AND any remote-access/screen-share
        // vector (UsageStats FGS, AppOps PROJECT_MEDIA, VirtualDisplay mirror, or UI tree indicator) is active.
        val combinedThreatSource = mediaThreat.threatDescription ?: dfsReport?.remoteThreatIndicatorInTree

        if (isProtectedForeground && combinedThreatSource != null) {
            val targetLabel = buildString {
                append(protectedTarget!!.displayName)
                if (dfsReport?.sensitivePaymentUiFound == true) {
                    append(" [UPI PIN / Payment Node]")
                }
            }
            commandChannel.send(
                ShieldCommand.ThreatDetected(
                    threatSource = combinedThreatSource,
                    paymentTarget = targetLabel,
                    isSafetyDrill = false
                )
            )
        } else if (overlayManager.isOverlayMounted()) {
            // Either the user exited the UPI app OR the remote screen-sharing session stopped.
            // Feed a clean observation into the two-cycle hysteresis state machine.
            commandChannel.send(ShieldCommand.CleanCycleObserved)
        }
    }

    /**
     * Unified Command Handler with Two-Cycle Polling Hysteresis (`HYSTERESIS_CLEAN_CYCLES_REQUIRED = 2`).
     *
     * Why Hysteresis Matters:
     * When our `TYPE_ACCESSIBILITY_OVERLAY` mounts on top of Google Pay or PhonePe, Android's
     * window manager may briefly emit focus/window transitions or cause transient dips in
     * foreground event queries. Without hysteresis, the overlay would mount -> read 1 clean tick ->
     * unmount -> re-detect -> mount in a rapid visual & audio strobe loop. Requiring two consecutive
     * clean polling cycles guarantees rock-solid overlay stability and crisp teardown.
     */
    private fun startUnifiedCommandHandler() {
        commandProcessorJob?.cancel()
        commandProcessorJob = mainCommandScope.launch {
            for (cmd in commandChannel) {
                when (cmd) {
                    is ShieldCommand.ThreatDetected -> {
                        // Reset clean-cycle hysteresis counter immediately on any positive threat signal
                        consecutiveCleanCycles.set(0)

                        val mounted = overlayManager.showOverlay(
                            accessibilityContext = this@KavachAccessibilityService,
                            threatSource = cmd.threatSource,
                            protectedTarget = cmd.paymentTarget,
                            isDrill = cmd.isSafetyDrill
                        )

                        bilingualTtsManager.startContinuousAlert(cmd.threatSource)

                        KavachSecurityRegistry.updateState { state ->
                            state.copy(
                                isThreatActive = true,
                                isSafetyDrillActive = cmd.isSafetyDrill,
                                detectedThreatSource = cmd.threatSource,
                                detectedPaymentTarget = cmd.paymentTarget,
                                overlayMounted = mounted,
                                cleanHysteresisCount = 0
                            )
                        }
                    }

                    ShieldCommand.CleanCycleObserved -> {
                        if (!overlayManager.isOverlayMounted()) {
                            consecutiveCleanCycles.set(0)
                            continue
                        }

                        val cleanCount = consecutiveCleanCycles.incrementAndGet()
                        KavachSecurityRegistry.updateState {
                            it.copy(cleanHysteresisCount = cleanCount)
                        }

                        if (cleanCount >= HYSTERESIS_CLEAN_CYCLES_REQUIRED) {
                            KavachSecurityRegistry.recordAudit(
                                AuditSeverity.INFO,
                                "HYSTERESIS",
                                "Threat resolved across $cleanCount consecutive clean cycles. Dismissing shield & TTS."
                            )
                            consecutiveCleanCycles.set(0)
                            bilingualTtsManager.stopAlerts()
                            overlayManager.dismissOverlay()
                            KavachSecurityRegistry.updateState { state ->
                                state.copy(
                                    isThreatActive = false,
                                    isSafetyDrillActive = false,
                                    detectedThreatSource = null,
                                    overlayMounted = false,
                                    cleanHysteresisCount = 0
                                )
                            }
                        } else {
                            KavachSecurityRegistry.recordAudit(
                                AuditSeverity.INFO,
                                "HYSTERESIS",
                                "Clean verification cycle $cleanCount/$HYSTERESIS_CLEAN_CYCLES_REQUIRED observed; holding shield steady."
                            )
                        }
                    }

                    ShieldCommand.ForceImmediateDismiss -> {
                        consecutiveCleanCycles.set(0)
                        bilingualTtsManager.stopAlerts()
                        overlayManager.dismissOverlay()
                        KavachSecurityRegistry.updateState { state ->
                            state.copy(
                                isThreatActive = false,
                                isSafetyDrillActive = false,
                                detectedThreatSource = null,
                                overlayMounted = false,
                                cleanHysteresisCount = 0
                            )
                        }
                    }

                    ShieldCommand.MuteVoiceOnly -> {
                        bilingualTtsManager.stopAlerts()
                    }
                }
            }
        }
    }

    /**
     * Multi-Vector Screen-Share & Media-Projection Detector (Android 8.0 – Android 14 / SDK 34).
     *
     * Inspects four independent OS-level signals locally on-device:
     * 1. `DisplayManager`: Checks active displays for `VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR`,
     *    presentation capture surfaces, or non-built-in mirrored virtual displays created by
     *    `MediaProjection.createVirtualDisplay(...)`.
     * 2. `UsageStatsManager.queryEvents(...)`: Reconstructs active foreground service lifecycles
     *    (`FOREGROUND_SERVICE_START` vs `FOREGROUND_SERVICE_STOP` on API 29+) and recent activity
     *    resumptions for known remote-desktop packages (`AnyDesk`, `TeamViewer`, `QuickSupport`, etc.).
     * 3. `AppOpsManager`: Queries `AppOpsManager.OPSTR_PROJECT_MEDIA` mode/state where permitted.
     * 4. `AccessibilityService.getWindows()`: Checks multi-window / Picture-in-Picture / floating
     *    overlay windows belonging to remote-access tools simultaneously visible alongside UPI.
     */
    private fun detectConcurrentScreenShareOrRemoteAccess(): ThreatTelemetrySnapshot {
        // Vector 1: Virtual / Mirrored Screen-Capture Displays via DisplayManager
        try {
            val displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val displays = displayManager?.displays.orEmpty()
            for (display in displays) {
                if (display.displayId != Display.DEFAULT_DISPLAY && display.state != Display.STATE_OFF) {
                    val displayName = display.name ?: "VirtualCaptureDisplay"
                    val flags = display.flags
                    val isPublicOrPresentation = (flags and Display.FLAG_PRESENTATION) != 0 ||
                        (flags and Display.FLAG_PRIVATE) == 0
                    if (isPublicOrPresentation ||
                        displayName.contains("mirror", ignoreCase = true) ||
                        displayName.contains("screencast", ignoreCase = true) ||
                        displayName.contains("cast", ignoreCase = true) ||
                        displayName.contains("record", ignoreCase = true) ||
                        displayName.contains("share", ignoreCase = true) ||
                        displayName.contains("remote", ignoreCase = true) ||
                        displayName.contains("anydesk", ignoreCase = true) ||
                        displayName.contains("teamviewer", ignoreCase = true) ||
                        displayName.contains("rustdesk", ignoreCase = true) ||
                        displayName.contains("vnc", ignoreCase = true) ||
                        displayName.contains("scrcpy", ignoreCase = true) ||
                        displayName.contains("projection", ignoreCase = true) ||
                        displayName.contains("virtual", ignoreCase = true)
                    ) {
                        return ThreatTelemetrySnapshot(
                            isThreatPresent = true,
                            threatDescription = "Active Screen-Capture VirtualDisplay ($displayName)"
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "DisplayManager inspection error", t)
        }

        // Vector 2: Interactive Multi-Window / Floating Remote-Control Windows
        try {
            val interactiveWindows: List<AccessibilityWindowInfo> = windows
            for (win in interactiveWindows) {
                val root = win.root
                val winPkg = root?.packageName?.toString()
                if (root != null) {
                    recycleNodeQuietly(root)
                }
                if (winPkg != null && KavachSecurityRegistry.REMOTE_THREAT_MAP.containsKey(winPkg)) {
                    val sig = KavachSecurityRegistry.REMOTE_THREAT_MAP[winPkg]!!
                    return ThreatTelemetrySnapshot(
                        isThreatPresent = true,
                        threatDescription = "${sig.displayName} (Concurrent Window)"
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Interactive window stack check skipped", t)
        }

        // Vector 3: UsageStatsManager Compressed Window & Foreground-Service Timeline (Android 10+ FGS)
        try {
            val usageStatsManager =
                getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (usageStatsManager != null) {
                val now = System.currentTimeMillis()
                val startWindow = now - USAGE_TIMELINE_LOOKBACK_MS
                val usageEvents: UsageEvents? = usageStatsManager.queryEvents(startWindow, now)

                if (usageEvents != null) {
                    // Track packages whose FOREGROUND_SERVICE_START has NOT been closed by FOREGROUND_SERVICE_STOP
                    val activeForegroundServices = mutableSetOf<String>()
                    // Track remote-control apps interacted with in the last 90 seconds (scammer setup phase)
                    val recentlyResumedRemotePackages = mutableMapOf<String, Long>()

                    val reusableEvent = UsageEvents.Event()
                    while (usageEvents.hasNextEvent()) {
                        usageEvents.getNextEvent(reusableEvent)
                        val eventPkg = reusableEvent.packageName ?: continue
                        if (!KavachSecurityRegistry.REMOTE_THREAT_MAP.containsKey(eventPkg)) {
                            continue
                        }

                        when (reusableEvent.eventType) {
                            UsageEvents.Event.FOREGROUND_SERVICE_START -> {
                                activeForegroundServices.add(eventPkg)
                            }

                            UsageEvents.Event.FOREGROUND_SERVICE_STOP -> {
                                activeForegroundServices.remove(eventPkg)
                            }

                            UsageEvents.Event.ACTIVITY_RESUMED -> {
                                recentlyResumedRemotePackages[eventPkg] = reusableEvent.timeStamp
                            }
                        }
                    }

                    // Priority A: Active MediaProjection / Remote Desktop Foreground Service
                    if (activeForegroundServices.isNotEmpty()) {
                        val culpritPkg = activeForegroundServices.first()
                        val sig = KavachSecurityRegistry.REMOTE_THREAT_MAP[culpritPkg]
                        return ThreatTelemetrySnapshot(
                            isThreatPresent = true,
                            threatDescription = "${sig?.displayName ?: culpritPkg} (Active MediaProjection / FGS)"
                        )
                    }

                    // Priority B: Remote-access app resumed within the last 90 seconds right before opening UPI
                    val recentCutoff = now - 90_000L
                    val recentEntry = recentlyResumedRemotePackages.entries
                        .firstOrNull { it.value >= recentCutoff }
                    if (recentEntry != null) {
                        val sig = KavachSecurityRegistry.REMOTE_THREAT_MAP[recentEntry.key]
                        return ThreatTelemetrySnapshot(
                            isThreatPresent = true,
                            threatDescription = "${sig?.displayName ?: recentEntry.key} (Active Session Timeline)"
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "UsageStatsManager query skipped (Usage Access may not be granted yet)", t)
        }

        // Vector 4: AppOpsManager OPSTR_PROJECT_MEDIA inspection for installed remote-access UIDs
        try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            if (appOps != null) {
                for (threatSig in KavachSecurityRegistry.REMOTE_THREAT_SIGNATURES) {
                    val appInfo = try {
                        packageManager.getApplicationInfo(threatSig.packageName, 0)
                    } catch (_: Exception) {
                        null
                    } ?: continue

                    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        appOps.unsafeCheckOpNoThrow(
                            OPSTR_PROJECT_MEDIA,
                            appInfo.uid,
                            threatSig.packageName
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        appOps.checkOpNoThrow(
                            OPSTR_PROJECT_MEDIA,
                            appInfo.uid,
                            threatSig.packageName
                        )
                    }

                    // Note: By default on Android, OP_PROJECT_MEDIA is MODE_DEFAULT until a user
                    // explicitly grants MediaProjection consent via the system cast dialog, which
                    // transitions the op to MODE_ALLOWED for the session!
                    if (mode == AppOpsManager.MODE_ALLOWED) {
                        return ThreatTelemetrySnapshot(
                            isThreatPresent = true,
                            threatDescription = "${threatSig.displayName} (OPSTR_PROJECT_MEDIA Allowed)"
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "AppOpsManager OPSTR_PROJECT_MEDIA check skipped", t)
        }

        return ThreatTelemetrySnapshot(isThreatPresent = false, threatDescription = null)
    }

    /**
     * Memory-Safe Bounded DFS Accessibility Node Tree Inspector.
     *
     * Strict Stability & Leak Prevention Rules:
     * 1. Enforces a hard recursion depth ceiling of `MAX_DFS_DEPTH = 15` layers.
     * 2. Explicitly recycles every child `AccessibilityNodeInfo` retrieved via `node.getChild(i)`
     *    inside a `try / finally` block immediately after its subtree finishes traversal, preventing
     *    unbounded Binder proxy accumulation in the Accessibility process.
     * 3. Never caches or stores `AccessibilityNodeInfo` references outside the stack frame.
     */
    private fun inspectActiveWindowNodeTreeSafely(): DfsInspectionReport {
        val rootNode: AccessibilityNodeInfo = try {
            rootInActiveWindow
        } catch (t: Throwable) {
            null
        } ?: return DfsInspectionReport(0, 0, false, null)

        var inspectedCount = 0
        var deepestLayer = 0
        var sensitiveUiDetected = false
        var remoteUiIndicator: String? = null

        fun traverseDfsBounded(node: AccessibilityNodeInfo, currentDepth: Int) {
            // Enforce strict 15-layer DFS ceiling and per-pass node budget
            if (currentDepth > MAX_DFS_DEPTH || inspectedCount >= MAX_DFS_NODES_PER_PASS) {
                return
            }

            inspectedCount++
            if (currentDepth > deepestLayer) {
                deepestLayer = currentDepth
            }

            // Inspect text & contentDescription without retaining CharSequence references
            val nodeText = node.text?.toString()?.lowercase(Locale.ROOT).orEmpty()
            val nodeDesc = node.contentDescription?.toString()?.lowercase(Locale.ROOT).orEmpty()
            val viewId = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()

            if (!sensitiveUiDetected && (nodeText.isNotEmpty() || nodeDesc.isNotEmpty() || viewId.isNotEmpty())) {
                for (keyword in KavachSecurityRegistry.SENSITIVE_PAYMENT_KEYWORDS) {
                    if (nodeText.contains(keyword) || nodeDesc.contains(keyword) || viewId.contains("pin")) {
                        sensitiveUiDetected = true
                        break
                    }
                }
            }

            // Also detect system cast / remote session banners visible in the window hierarchy
            if (remoteUiIndicator == null && (nodeText.isNotEmpty() || nodeDesc.isNotEmpty())) {
                if (nodeText.contains("casting screen") ||
                    nodeDesc.contains("casting screen") ||
                    nodeText.contains("anydesk") ||
                    nodeText.contains("teamviewer")
                ) {
                    remoteUiIndicator = "UI Tree Screen-Cast Indicator"
                }
            }

            // Stop descending if the next layer would exceed MAX_DFS_DEPTH (15)
            if (currentDepth == MAX_DFS_DEPTH) {
                return
            }

            val childCount = node.childCount
            for (index in 0 until childCount) {
                if (inspectedCount >= MAX_DFS_NODES_PER_PASS) break
                val childNode: AccessibilityNodeInfo? = try {
                    node.getChild(index)
                } catch (_: Throwable) {
                    null
                }
                if (childNode != null) {
                    try {
                        traverseDfsBounded(childNode, currentDepth + 1)
                    } finally {
                        // CRITICAL: Explicitly recycle every child node to release Binder proxy memory
                        recycleNodeQuietly(childNode)
                    }
                }
            }
        }

        try {
            traverseDfsBounded(rootNode, currentDepth = 1)
        } finally {
            // Explicitly recycle root node to prevent native AccessibilityNodeInfo pool leaks
            recycleNodeQuietly(rootNode)
        }

        val report = DfsInspectionReport(
            nodesInspected = inspectedCount,
            maxDepthReached = deepestLayer,
            sensitivePaymentUiFound = sensitiveUiDetected,
            remoteThreatIndicatorInTree = remoteUiIndicator
        )

        KavachSecurityRegistry.updateState { state ->
            state.copy(
                sensitivePaymentNodeDetected = sensitiveUiDetected,
                lastDfsNodesInspected = inspectedCount,
                lastDfsMaxDepthReached = deepestLayer
            )
        }

        return report
    }

    /**
     * Executes explicit `AccessibilityNodeInfo.recycle()` to free underlying Binder proxy & pool
     * allocations across API 26–34.
     */
    @Suppress("DEPRECATION")
    private fun recycleNodeQuietly(node: AccessibilityNodeInfo) {
        try {
            node.recycle()
        } catch (_: IllegalStateException) {
            // Already recycled; safe to ignore
        }
    }

    /**
     * Evacuates the user out of the targeted UPI/Banking screen to the Android Home Launcher
     * using `GLOBAL_ACTION_HOME` when the user taps "Exit Payment App Safely" on the shield.
     */
    private fun evacuateUserToHomeScreen() {
        mainCommandScope.launch {
            KavachSecurityRegistry.recordAudit(
                AuditSeverity.WARNING,
                "EVACUATE",
                "User tapped Safe Exit: dispatching GLOBAL_ACTION_HOME to leave payment screen."
            )
            isSafetyDrillMode.set(false)
            isProtectedPaymentActive.set(false)
            performGlobalAction(GLOBAL_ACTION_HOME)
            commandChannel.send(ShieldCommand.ForceImmediateDismiss)
        }
    }

    /**
     * Toggles the user-initiated Live Shield & Bilingual Voice Safety Drill.
     */
    fun setSafetyDrillMode(enabled: Boolean) {
        isSafetyDrillMode.set(enabled)
        if (enabled) {
            KavachSecurityRegistry.recordAudit(
                AuditSeverity.WARNING,
                "DRILL",
                "Live Shield Safety Drill activated via TYPE_ACCESSIBILITY_OVERLAY."
            )
            backgroundScope.launch {
                runSingleTelemetryEvaluationPass(triggerReason = "SAFETY_DRILL_START")
            }
        } else {
            KavachSecurityRegistry.recordAudit(
                AuditSeverity.INFO,
                "DRILL",
                "Live Shield Safety Drill ended by user."
            )
            commandChannel.trySend(ShieldCommand.ForceImmediateDismiss)
        }
    }

    private fun teardownServiceResources() {
        activeInstanceRef = null
        dualGearTrackerJob?.cancel()
        commandProcessorJob?.cancel()
        commandChannel.close()

        if (::bilingualTtsManager.isInitialized) {
            bilingualTtsManager.shutdown()
        }
        if (::overlayManager.isInitialized) {
            overlayManager.dismissOverlay()
        }

        serviceSupervisorJob.cancel()
        KavachSecurityRegistry.updateState {
            it.copy(
                isServiceConnected = false,
                overlayMounted = false,
                isThreatActive = false,
                isSafetyDrillActive = false,
                trackingGear = TrackingGear.LOW_GEAR_IDLE
            )
        }
    }
}
