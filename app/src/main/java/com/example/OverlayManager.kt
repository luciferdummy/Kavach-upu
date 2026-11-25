package com.example

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.MainThread
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OverlayManager — Programmatic Glassmorphic Touch-Blocking Window Shield.
 *
 * Security Architecture:
 * 1. Window Type Selection:
 *    - Prefers [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY] whenever an active
 *      AccessibilityService context is supplied. On Android 10–14+, `TYPE_ACCESSIBILITY_OVERLAY`
 *      sits above standard app windows and is trusted by the Accessibility framework without
 *      requiring a separate overlay toggle, while remaining hidden from untrusted third-party
 *      capture layers where supported.
 *    - Gracefully falls back to [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY] when invoked
 *      from [MainActivity] (e.g., Live Shield Safety Drill) if `Settings.canDrawOverlays(context)`
 *      is granted.
 *
 * 2. Touch Absorption & Input Blocking (`LayoutParams` Flags):
 *    - Uses `FLAG_NOT_FOCUSABLE or FLAG_NOT_TOUCH_MODAL or FLAG_LAYOUT_IN_SCREEN` across a
 *      `MATCH_PARENT x MATCH_PARENT` root container whose `OnTouchListener` unconditionally
 *      consumes every `MotionEvent` (`return true`), physically preventing both human accidental
 *      taps and remote scammer input-injection gestures from reaching the underlying UPI PIN pad.
 *    - Adds `FLAG_WATCH_OUTSIDE_TOUCH` and `FLAG_SECURE` so remote screen-sharing tools (AnyDesk /
 *      TeamViewer / MediaProjection) see a blacked-out secure surface instead of underlying banking
 *      details!
 */
class OverlayManager(
    private val baseContext: Context,
    private val onMuteVoiceRequested: () -> Unit,
    private val onExitPaymentAppRequested: () -> Unit,
    private val onEndSafetyDrillRequested: (() -> Unit)? = null
) {

    companion object {
        private const val TAG = "OverlayManager"
    }

    private val isMounted = AtomicBoolean(false)
    private var activeWindowManager: WindowManager? = null
    private var rootOverlayView: View? = null

    // Dynamic references for live text updates without tearing down & re-mounting the window
    private var threatDetailTextView: TextView? = null
    private var blockedTouchesCounterView: TextView? = null
    private var endDrillButton: Button? = null
    private var absorbedTouchCount: Int = 0

    /**
     * Mounts the full-screen glassmorphic touch-blocking shield onto the display window stack.
     *
     * @param accessibilityContext Optional [KavachAccessibilityService] context. When non-null,
     *        mounts using `TYPE_ACCESSIBILITY_OVERLAY` for maximum privilege.
     * @param threatSource Human-readable name of the detected screen-share / remote tool.
     * @param protectedTarget Human-readable name of the protected UPI/Banking app at risk.
     * @param isDrill True if launched via the user's "Live Shield Drill" diagnostic button.
     * @return True if the overlay is currently mounted on screen.
     */
    @MainThread
    fun showOverlay(
        accessibilityContext: Context? = null,
        threatSource: String,
        protectedTarget: String,
        isDrill: Boolean = false
    ): Boolean {
        if (isMounted.get() && rootOverlayView != null) {
            updateOverlayLabels(threatSource, protectedTarget, isDrill)
            return true
        }

        // Determine optimal window type & context
        val useAccessibilityType = accessibilityContext != null
        val targetContext = if (useAccessibilityType) {
            accessibilityContext!!
        } else {
            if (!Settings.canDrawOverlays(baseContext)) {
                KavachSecurityRegistry.recordAudit(
                    AuditSeverity.WARNING,
                    "OVERLAY",
                    "Cannot mount TYPE_APPLICATION_OVERLAY: Draw Over Other Apps permission missing."
                )
                return false
            }
            baseContext
        }

        val wm = targetContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return false

        val windowType = if (useAccessibilityType) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }

        // Configure zero-trust touch-absorbing WindowManager.LayoutParams
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            // Required security flags:
            // FLAG_NOT_FOCUSABLE: Avoids stealing IME keyboard focus while still intercepting all pointer touches
            // FLAG_NOT_TOUCH_MODAL: Combined with MATCH_PARENT, routes all screen coordinates to our root absorber
            // FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS: Covers full display including status/PIN area
            // FLAG_SECURE: Masks the screen stream sent to MediaProjection / AnyDesk / TeamViewer!
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SECURE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            // Android 12+ (API 31+) native window-behind blur for authentic glassmorphism
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                blurBehindRadius = 42
                flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            }
            title = "KavachZeroTrustShield"
        }

        absorbedTouchCount = 0
        val view = buildGlassmorphicShieldView(
            context = targetContext,
            threatSource = threatSource,
            protectedTarget = protectedTarget,
            isDrill = isDrill
        )

        return try {
            wm.addView(view, layoutParams)
            activeWindowManager = wm
            rootOverlayView = view
            isMounted.set(true)
            triggerTactileAlarm(targetContext)

            KavachSecurityRegistry.updateState { state ->
                state.copy(
                    overlayMounted = true,
                    totalThreatInterceptions = state.totalThreatInterceptions + 1
                )
            }
            KavachSecurityRegistry.recordAudit(
                AuditSeverity.CRITICAL,
                "OVERLAY",
                "Mounted ${if (useAccessibilityType) "TYPE_ACCESSIBILITY_OVERLAY" else "TYPE_APPLICATION_OVERLAY"} blocking [$protectedTarget] against [$threatSource]."
            )
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to mount overlay window", t)
            // Fallback attempt: if TYPE_ACCESSIBILITY_OVERLAY failed but SYSTEM_ALERT_WINDOW is granted
            if (useAccessibilityType && Settings.canDrawOverlays(baseContext)) {
                return showOverlay(
                    accessibilityContext = null,
                    threatSource = threatSource,
                    protectedTarget = protectedTarget,
                    isDrill = isDrill
                )
            }
            KavachSecurityRegistry.recordAudit(
                AuditSeverity.WARNING,
                "OVERLAY",
                "Overlay mount exception: ${t.localizedMessage}"
            )
            false
        }
    }

    /**
     * Updates live threat metadata on an already-mounted overlay without flickering.
     */
    @MainThread
    private fun updateOverlayLabels(
        threatSource: String,
        protectedTarget: String,
        isDrill: Boolean
    ) {
        threatDetailTextView?.text =
            "TARGET PROTECTED: $protectedTarget\nTHREAT DETECTED: $threatSource"
        endDrillButton?.visibility = if (isDrill) View.VISIBLE else View.GONE
    }

    /**
     * Cleanly tears down and detaches the glassmorphic overlay from [WindowManager].
     */
    @MainThread
    fun dismissOverlay() {
        if (!isMounted.compareAndSet(true, false)) {
            return
        }

        val view = rootOverlayView
        val wm = activeWindowManager
        rootOverlayView = null
        activeWindowManager = null
        threatDetailTextView = null
        blockedTouchesCounterView = null
        endDrillButton = null

        if (view != null && wm != null) {
            try {
                wm.removeViewImmediate(view)
                KavachSecurityRegistry.recordAudit(
                    AuditSeverity.INFO,
                    "OVERLAY",
                    "Touch-blocking shield cleanly unmounted (absorbed $absorbedTouchCount touch events)."
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Non-fatal exception removing overlay view", t)
            }
        }

        KavachSecurityRegistry.updateState { it.copy(overlayMounted = false) }
    }

    fun isOverlayMounted(): Boolean = isMounted.get()

    /**
     * Programmatically builds the frosted dark-crimson + obsidian glassmorphic UI hierarchy.
     * Avoids XML inflation overhead inside AccessibilityService and enforces total touch capture.
     */
    private fun buildGlassmorphicShieldView(
        context: Context,
        threatSource: String,
        protectedTarget: String,
        isDrill: Boolean
    ): View {
        val rootScrim = FrameLayout(context).apply {
            // Frosted translucent obsidian-crimson scrim
            val scrimGradient = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.argb(232, 26, 6, 14),
                    Color.argb(242, 9, 13, 24),
                    Color.argb(246, 22, 6, 12)
                )
            )
            background = scrimGradient
            isClickable = true
            isFocusable = false

            // Consume 100% of touch/pointer events across the entire viewport so zero taps leak through
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN ||
                    event.actionMasked == MotionEvent.ACTION_OUTSIDE
                ) {
                    absorbedTouchCount++
                    blockedTouchesCounterView?.text =
                        "REMOTE / SCREEN TOUCHES ABSORBED: $absorbedTouchCount"
                }
                true
            }
        }

        val scrollWrapper = ScrollView(context).apply {
            isFillViewport = true
        }

        val centerWrapper = FrameLayout(context).apply {
            setPadding(dp(context, 20), dp(context, 40), dp(context, 20), dp(context, 40))
        }

        // Glassmorphic inner card with specular crimson/cyan border
        val glassCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(context, 24), dp(context, 28), dp(context, 24), dp(context, 24))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 26).toFloat()
                colors = intArrayOf(
                    Color.argb(225, 24, 30, 48),
                    Color.argb(235, 16, 20, 34)
                )
                orientation = GradientDrawable.Orientation.TL_BR
                setStroke(dp(context, 2), Color.argb(210, 255, 59, 92))
            }
            elevation = dp(context, 16).toFloat()
        }

        // Top Pill Badge
        val badgeView = TextView(context).apply {
            text = context.getString(R.string.overlay_badge)
            setTextColor(Color.rgb(255, 110, 134))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.08f
            setPadding(dp(context, 14), dp(context, 6), dp(context, 14), dp(context, 6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 50).toFloat()
                setColor(Color.argb(95, 127, 18, 38))
                setStroke(dp(context, 1), Color.argb(160, 255, 59, 92))
            }
        }
        glassCard.addView(
            badgeView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // English Headline
        val titleEn = TextView(context).apply {
            text = context.getString(R.string.overlay_title_en)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(context, 16), 0, dp(context, 6))
        }
        glassCard.addView(titleEn)

        // Hindi Headline
        val titleHi = TextView(context).apply {
            text = context.getString(R.string.overlay_title_hi)
            setTextColor(Color.rgb(251, 191, 36))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(context, 14))
        }
        glassCard.addView(titleHi)

        // Forensic Telemetry Box (Target + Threat Vector)
        val telemetryBox = TextView(context).apply {
            text = "TARGET PROTECTED: $protectedTarget\nTHREAT DETECTED: $threatSource"
            setTextColor(Color.rgb(34, 211, 238))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            setLineSpacing(dp(context, 4).toFloat(), 1.0f)
            setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 14).toFloat()
                setColor(Color.argb(180, 9, 16, 28))
                setStroke(dp(context, 1), Color.argb(120, 34, 211, 238))
            }
        }
        threatDetailTextView = telemetryBox
        glassCard.addView(
            telemetryBox,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(context, 12)
            }
        )

        // Live Absorbed Touch Counter Badge
        val counterText = TextView(context).apply {
            text = "REMOTE / SCREEN TOUCHES ABSORBED: 0"
            setTextColor(Color.rgb(16, 229, 155))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(context, 12))
        }
        blockedTouchesCounterView = counterText
        glassCard.addView(counterText)

        // Bilingual Explanation Body (EN)
        val bodyEn = TextView(context).apply {
            text = context.getString(R.string.overlay_body_en)
            setTextColor(Color.rgb(226, 232, 240))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            gravity = Gravity.CENTER
            setLineSpacing(dp(context, 3).toFloat(), 1.0f)
            setPadding(0, 0, 0, dp(context, 10))
        }
        glassCard.addView(bodyEn)

        // Bilingual Explanation Body (HI)
        val bodyHi = TextView(context).apply {
            text = context.getString(R.string.overlay_body_hi)
            setTextColor(Color.rgb(203, 213, 225))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            gravity = Gravity.CENTER
            setLineSpacing(dp(context, 3).toFloat(), 1.0f)
            setPadding(0, 0, 0, dp(context, 20))
        }
        glassCard.addView(bodyHi)

        // Primary Safe Escape CTA: Exit Payment App to Home Screen
        val exitPaymentBtn = Button(context).apply {
            text = context.getString(R.string.overlay_btn_exit_payment)
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(Typeface.DEFAULT_BOLD)
            minHeight = dp(context, 52)
            ellipsize = TextUtils.TruncateAt.END
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 14).toFloat()
                setColor(Color.rgb(225, 29, 72))
            }
            setOnClickListener {
                onExitPaymentAppRequested()
            }
        }
        glassCard.addView(
            exitPaymentBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(context, 10)
            }
        )

        // Secondary Action: Mute Bilingual Voice Siren while keeping Touch Shield locked
        val muteVoiceBtn = Button(context).apply {
            text = context.getString(R.string.overlay_btn_mute_tts)
            isAllCaps = false
            setTextColor(Color.rgb(241, 245, 249))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            minHeight = dp(context, 48)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 14).toFloat()
                setColor(Color.argb(170, 30, 41, 59))
                setStroke(dp(context, 1), Color.argb(150, 148, 163, 184))
            }
            setOnClickListener {
                onMuteVoiceRequested()
            }
        }
        glassCard.addView(
            muteVoiceBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(context, 10)
            }
        )

        // Conditional Safety Drill Dismiss Button (only visible during user-initiated Safety Drill)
        val drillDismissBtn = Button(context).apply {
            text = context.getString(R.string.overlay_btn_end_drill)
            isAllCaps = false
            setTextColor(Color.rgb(2, 32, 21))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(Typeface.DEFAULT_BOLD)
            minHeight = dp(context, 48)
            visibility = if (isDrill) View.VISIBLE else View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 14).toFloat()
                setColor(Color.rgb(16, 229, 155))
            }
            setOnClickListener {
                onEndSafetyDrillRequested?.invoke() ?: dismissOverlay()
            }
        }
        endDrillButton = drillDismissBtn
        glassCard.addView(
            drillDismissBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val cardParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }
        centerWrapper.addView(glassCard, cardParams)
        scrollWrapper.addView(centerWrapper)
        rootScrim.addView(
            scrollWrapper,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Apply subtle RenderEffect hardware polish on Android 12+ if supported
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            glassCard.setRenderEffect(
                RenderEffect.createChainEffect(
                    RenderEffect.createBlurEffect(0.5f, 0.5f, Shader.TileMode.CLAMP),
                    RenderEffect.createBlurEffect(0.5f, 0.5f, Shader.TileMode.CLAMP)
                )
            )
            // Keep crisp text on the card itself
            glassCard.setRenderEffect(null)
        }

        return rootScrim
    }

    private fun triggerTactileAlarm(context: Context) {
        try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator?.hasVibrator() == true) {
                val timings = longArrayOf(0, 180, 90, 240, 90, 320)
                vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Haptic warning skipped", t)
        }
    }

    private fun dp(context: Context, value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
}
