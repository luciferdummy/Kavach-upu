package com.example

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Represents a known UPI or Mobile Banking application protected by Kavach-UPI.
 */
data class ProtectedPaymentTarget(
    val packageName: String,
    val displayName: String,
    val category: String
)

/**
 * Represents a known Remote Desktop, Screen-Sharing, or Screen-Mirroring package signature.
 */
data class RemoteThreatSignature(
    val packageName: String,
    val displayName: String,
    val riskVector: String
)

/**
 * Dual-gear coroutine polling cadence:
 * - LOW_GEAR_IDLE: 1500ms low-power heartbeat when user is outside financial apps.
 * - HIGH_GEAR_COMBAT: 350ms aggressive sub-second polling when a UPI/Banking window is active
 *   or an active threat is being mitigated.
 */
enum class TrackingGear(val intervalMs: Long, val label: String) {
    LOW_GEAR_IDLE(1_500L, "LOW-GEAR • 1500ms"),
    HIGH_GEAR_COMBAT(350L, "HIGH-GEAR • 350ms")
}

/**
 * Severity levels for the strictly on-device zero-trust ring buffer log.
 */
enum class AuditSeverity {
    INFO,
    WARNING,
    CRITICAL
}

/**
 * Immutable security telemetry entry stored exclusively in volatile RAM (never written to network).
 */
data class SecurityAuditEvent(
    val id: Long,
    val timestampFormatted: String,
    val severity: AuditSeverity,
    val tag: String,
    val message: String
)

/**
 * Snapshot of Kavach-UPI's live edge-computed protection state observed by MainActivity Compose UI.
 */
data class KavachRuntimeState(
    val isServiceConnected: Boolean = false,
    val trackingGear: TrackingGear = TrackingGear.LOW_GEAR_IDLE,
    val activeForegroundPackage: String = "None",
    val isProtectedAppInForeground: Boolean = false,
    val sensitivePaymentNodeDetected: Boolean = false,
    val isThreatActive: Boolean = false,
    val isSafetyDrillActive: Boolean = false,
    val detectedThreatSource: String? = null,
    val detectedPaymentTarget: String? = null,
    val overlayMounted: Boolean = false,
    val ttsInitialized: Boolean = false,
    val cleanHysteresisCount: Int = 0,
    val lastDfsNodesInspected: Int = 0,
    val lastDfsMaxDepthReached: Int = 0,
    val totalThreatInterceptions: Int = 0
)

/**
 * Thread-safe, strictly in-memory state bus connecting [KavachAccessibilityService],
 * [OverlayManager], [BilingualTTSManager], and [MainActivity] without Binder leaks or IPC overhead.
 */
object KavachSecurityRegistry {

    /**
     * Canonical allowlist of Indian UPI & Mobile Banking packages triggering High-Gear protection.
     */
    val PROTECTED_PAYMENT_TARGETS: List<ProtectedPaymentTarget> = listOf(
        ProtectedPaymentTarget("com.google.android.apps.nbu.paisa.user", "Google Pay (GPay)", "UPI SuperApp"),
        ProtectedPaymentTarget("com.phonepe.app", "PhonePe", "UPI SuperApp"),
        ProtectedPaymentTarget("net.one97.paytm", "Paytm", "UPI & Wallet"),
        ProtectedPaymentTarget("in.org.npci.upiapp", "BHIM UPI", "NPCI Official"),
        ProtectedPaymentTarget("in.amazon.mShop.android.shopping", "Amazon Pay", "UPI & Commerce"),
        ProtectedPaymentTarget("com.whatsapp", "WhatsApp Pay", "Messaging UPI"),
        ProtectedPaymentTarget("com.dreamplug.androidapp", "CRED", "Credit & UPI"),
        ProtectedPaymentTarget("com.sbi.lotusintouch", "YONO SBI", "Core Banking"),
        ProtectedPaymentTarget("com.csam.icici.bank.imobile", "iMobile Pay (ICICI)", "Core Banking"),
        ProtectedPaymentTarget("com.snapwork.hdfc", "HDFC Bank Mobile", "Core Banking"),
        ProtectedPaymentTarget("com.axis.mobile", "Axis Mobile", "Core Banking"),
        ProtectedPaymentTarget("com.kotak.mobile.k811", "Kotak 811", "Core Banking")
    )

    val PROTECTED_PACKAGE_MAP: Map<String, ProtectedPaymentTarget> =
        PROTECTED_PAYMENT_TARGETS.associateBy { it.packageName }

    /**
     * Known Remote-Access Trojans / Legitimate Remote Support tools weaponized in UPI scams.
     */
    val REMOTE_THREAT_SIGNATURES: List<RemoteThreatSignature> = listOf(
        RemoteThreatSignature("com.anydesk.anydeskandroid", "AnyDesk Remote Control", "Full Screen + Input Injection"),
        RemoteThreatSignature("com.teamviewer.quicksupport.market", "TeamViewer QuickSupport", "Remote Screen Projection"),
        RemoteThreatSignature("com.teamviewer.teamviewer.market.mobile", "TeamViewer Remote", "Remote Desktop Session"),
        RemoteThreatSignature("com.teamviewer.host.market", "TeamViewer Host", "Unattended Screen Access"),
        RemoteThreatSignature("com.carriez.flutter_hbb", "RustDesk Remote", "Open-Source MediaProjection"),
        RemoteThreatSignature("com.realvnc.viewer.android", "VNC Viewer / Server", "Framebuffer Mirroring"),
        RemoteThreatSignature("com.splashtop.sos", "Splashtop SOS", "Remote Support Stream"),
        RemoteThreatSignature("com.zoho.assist.agent", "Zoho Assist Customer", "Remote Screen Sharing"),
        RemoteThreatSignature("com.awesun.control", "AweSun Remote", "Remote Desktop Stream"),
        RemoteThreatSignature("com.airdroid.airsos", "AirDroid Remote Support", "Screen Cast + Gesture Guide"),
        RemoteThreatSignature("us.zoom.videomeetings", "Zoom Screen Share", "Live Meeting Screen Cast"),
        RemoteThreatSignature("com.google.android.apps.tachyon", "Google Meet Screen Share", "Live Call Screen Cast"),
        RemoteThreatSignature("com.discord", "Discord Screen Stream", "Live Voice/Screen Cast")
    )

    val REMOTE_THREAT_MAP: Map<String, RemoteThreatSignature> =
        REMOTE_THREAT_SIGNATURES.associateBy { it.packageName }

    /**
     * Sensitive UI keywords (English & Hindi) within UPI / NetBanking PIN entry & transfer screens.
     * Used during bounded 15-layer DFS accessibility node inspection.
     */
    val SENSITIVE_PAYMENT_KEYWORDS: List<String> = listOf(
        "enter upi pin",
        "upi pin",
        "4-digit",
        "6-digit",
        "pay ₹",
        "send money",
        "authorize payment",
        "confirm payment",
        "mpin",
        "otp",
        "पिन दर्ज करें",
        "भुगतान करें"
    )

    private const val MAX_AUDIT_ENTRIES = 60
    private var nextEventId = 1L

    private val _runtimeState = MutableStateFlow(KavachRuntimeState())
    val runtimeState: StateFlow<KavachRuntimeState> = _runtimeState.asStateFlow()

    private val _auditLog = MutableStateFlow<List<SecurityAuditEvent>>(
        listOf(
            createEvent(
                severity = AuditSeverity.INFO,
                tag = "BOOT",
                message = "Kavach-UPI Zero-Trust Engine initialized in 100% offline edge mode."
            )
        )
    )
    val auditLog: StateFlow<List<SecurityAuditEvent>> = _auditLog.asStateFlow()

    fun updateState(transform: (KavachRuntimeState) -> KavachRuntimeState) {
        _runtimeState.update(transform)
    }

    @Synchronized
    fun recordAudit(severity: AuditSeverity, tag: String, message: String) {
        val entry = createEvent(severity, tag, message)
        _auditLog.update { existing ->
            (listOf(entry) + existing).take(MAX_AUDIT_ENTRIES)
        }
    }

    @Synchronized
    fun clearAuditLog() {
        _auditLog.value = listOf(
            createEvent(
                severity = AuditSeverity.INFO,
                tag = "AUDIT",
                message = "In-memory security audit log cleared by user."
            )
        )
    }

    private fun createEvent(
        severity: AuditSeverity,
        tag: String,
        message: String
    ): SecurityAuditEvent {
        val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val id = nextEventId++
        return SecurityAuditEvent(
            id = id,
            timestampFormatted = formatter.format(Date()),
            severity = severity,
            tag = tag,
            message = message
        )
    }
}
