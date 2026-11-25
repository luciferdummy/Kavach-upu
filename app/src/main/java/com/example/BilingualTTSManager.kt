package com.example

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BilingualTTSManager — Continuous English & Hindi Voice Siren Engine.
 *
 * Security Rationale:
 * Remote-access financial scammers rely on social engineering over a phone call ("Sir, click here
 * to receive your cashback / KYC update") while watching the victim's screen via AnyDesk or
 * TeamViewer. Visual warnings alone can sometimes be rationalized away by scammers claiming it is
 * a "routine server popup". A loud, alternating English + Hindi spoken voice warning cuts directly
 * through phone-call manipulation and alerts both the user and nearby family members.
 *
 * Resource Lifecycle:
 * - Uses [AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY] & [AudioAttributes.CONTENT_TYPE_SPEECH]
 *   so warnings remain audible during active voice/VoIP scam calls.
 * - Runs continuous bilingual loop inside a dedicated [SupervisorJob] coroutine and flushes
 *   speech queues immediately upon threat clearance or user mute.
 */
class BilingualTTSManager(context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "BilingualTTSManager"
        private const val ALERT_LOOP_PAUSE_MS = 9_500L

        private const val ENGLISH_WARNING =
            "Warning! Screen sharing detected! Stop your UPI payment immediately! Do not enter your UPI PIN!"

        private const val HINDI_WARNING =
            "सावधान! आपकी स्क्रीन शेयर हो रही है! कृपया अपना यूपीआई भुगतान तुरंत रोकें और पिन दर्ज न करें!"

        private const val HINDI_TRANSLITERATED_FALLBACK =
            "Savdhaan! Aapki screen share ho rahi hai! Kripya apna UPI payment turant rokein!"
    }

    private val appContext: Context = context.applicationContext
    private val ttsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var ttsEngine: TextToSpeech? = null
    private val isEngineReady = AtomicBoolean(false)
    private val isHindiSupported = AtomicBoolean(false)
    private val isAlertLoopRunning = AtomicBoolean(false)
    private var alertLoopJob: Job? = null

    private val englishLocale: Locale = Locale("en", "IN")
    private val hindiLocale: Locale = Locale("hi", "IN")

    init {
        try {
            ttsEngine = TextToSpeech(appContext, this)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to instantiate TextToSpeech engine", t)
            KavachSecurityRegistry.recordAudit(
                AuditSeverity.WARNING,
                "TTS",
                "TextToSpeech engine unavailable on this device image: ${t.localizedMessage}"
            )
        }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            isEngineReady.set(false)
            KavachSecurityRegistry.updateState { it.copy(ttsInitialized = false) }
            KavachSecurityRegistry.recordAudit(
                AuditSeverity.WARNING,
                "TTS",
                "TextToSpeech initialization returned status=$status"
            )
            return
        }

        val engine = ttsEngine ?: return

        // Configure high-priority accessibility speech audio attributes
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        engine.setAudioAttributes(audioAttributes)
        engine.setSpeechRate(0.98f)
        engine.setPitch(1.04f)

        // Probe Hindi ("hi-IN") voice availability on device
        val hiAvailability = engine.isLanguageAvailable(hindiLocale)
        val hindiAvailable = hiAvailability >= TextToSpeech.LANG_AVAILABLE
        isHindiSupported.set(hindiAvailable)

        // Default initial language to Indian English or US English fallback
        val enResult = engine.setLanguage(englishLocale)
        if (enResult == TextToSpeech.LANG_MISSING_DATA || enResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.language = Locale.US
        }

        isEngineReady.set(true)
        KavachSecurityRegistry.updateState { it.copy(ttsInitialized = true) }
        KavachSecurityRegistry.recordAudit(
            AuditSeverity.INFO,
            "TTS",
            "Bilingual TTS ready (EN-IN active, HI-IN native=${hindiAvailable})"
        )
    }

    /**
     * Starts continuous bilingual (English + Hindi) spoken alerts while an active threat persists.
     * Safe to invoke idempotently; duplicate calls while already looping are ignored.
     */
    fun startContinuousAlert(threatName: String) {
        if (!isAlertLoopRunning.compareAndSet(false, true)) {
            return
        }

        alertLoopJob?.cancel()
        alertLoopJob = ttsScope.launch {
            KavachSecurityRegistry.recordAudit(
                AuditSeverity.CRITICAL,
                "TTS-SIREN",
                "Started bilingual EN + HI voice alert loop for threat: $threatName"
            )

            while (isActive && isAlertLoopRunning.get()) {
                speakBilingualCycleOnce(threatName)
                delay(ALERT_LOOP_PAUSE_MS)
            }
        }
    }

    /**
     * Plays a single bilingual warning sample (used by the Dashboard "Test EN/HI Voice Alert" button).
     */
    fun playSingleDiagnosticSample() {
        ttsScope.launch {
            if (!isEngineReady.get()) {
                KavachSecurityRegistry.recordAudit(
                    AuditSeverity.WARNING,
                    "TTS-TEST",
                    "TTS engine still initializing or unsupported in current emulator image."
                )
                return@launch
            }
            KavachSecurityRegistry.recordAudit(
                AuditSeverity.INFO,
                "TTS-TEST",
                "Playing single bilingual English + Hindi voice alert diagnostic."
            )
            speakBilingualCycleOnce("Diagnostic Test")
        }
    }

    /**
     *Queues English utterance (QUEUE_FLUSH) immediately followed by Hindi utterance (QUEUE_ADD).
     */
    private fun speakBilingualCycleOnce(threatContext: String) {
        val engine = ttsEngine ?: return
        if (!isEngineReady.get()) return

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }

        try {
            // 1. Speak English Warning (Flushes stale utterances from prior cycle)
            val enStatus = engine.isLanguageAvailable(englishLocale)
            engine.language = if (enStatus >= TextToSpeech.LANG_AVAILABLE) englishLocale else Locale.US
            val contextualEnglish = if (threatContext.isNotBlank() && threatContext != "Diagnostic Test") {
                "Security Alert! $threatContext screen sharing is active! $ENGLISH_WARNING"
            } else {
                ENGLISH_WARNING
            }
            engine.speak(
                contextualEnglish,
                TextToSpeech.QUEUE_FLUSH,
                params,
                "kavach_utt_en_${System.currentTimeMillis()}"
            )

            // 2. Queue Hindi Warning immediately after English (`Locale("hi", "IN")`)
            if (isHindiSupported.get()) {
                engine.language = hindiLocale
                engine.speak(
                    HINDI_WARNING,
                    TextToSpeech.QUEUE_ADD,
                    params,
                    "kavach_utt_hi_${System.currentTimeMillis()}"
                )
            } else {
                // Romanized Hindi fallback so non-Devanagari TTS engines still vocalize Hindi warning
                engine.speak(
                    HINDI_TRANSLITERATED_FALLBACK,
                    TextToSpeech.QUEUE_ADD,
                    params,
                    "kavach_utt_hi_fallback_${System.currentTimeMillis()}"
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error queuing bilingual speech cycle", t)
        }
    }

    /**
     * Immediately halts the continuous alert coroutine and flushes the hardware TTS audio buffer.
     */
    fun stopAlerts() {
        val wasRunning = isAlertLoopRunning.getAndSet(false)
        alertLoopJob?.cancel()
        alertLoopJob = null

        try {
            if (isEngineReady.get()) {
                ttsEngine?.stop()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error stopping TTS playback", t)
        }

        if (wasRunning) {
            KavachSecurityRegistry.recordAudit(
                AuditSeverity.INFO,
                "TTS-SIREN",
                "Bilingual voice siren halted and speech queue flushed."
            )
        }
    }

    /**
     * Releases Binder connections to the system TTS service to prevent memory/service leaks.
     */
    fun shutdown() {
        stopAlerts()
        isEngineReady.set(false)
        KavachSecurityRegistry.updateState { it.copy(ttsInitialized = false) }
        try {
            ttsEngine?.shutdown()
        } catch (t: Throwable) {
            Log.w(TAG, "Error shutting down TextToSpeech", t)
        } finally {
            ttsEngine = null
            ttsScope.cancel()
        }
    }
}
