/*
 * SPDX-FileCopyrightText: The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.lights

import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.UserHandle
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import kotlin.math.max

/**
 * Orchestrates the rear RGB light. Priority: incoming call > camera >
 * notification pulse > game/music audio > game static effect > off.
 *
 * Game and music lighting react to real output-mix audio: a 100ms beat
 * loop reads the Visualizer FFT, flashes on onsets (energy spike over a
 * slow baseline) and rides the level with fast attack / slow release.
 * When audio stops, games fall back to their static effect and music
 * goes dark.
 */
class BacklightService : Service() {

    private lateinit var prefs: BacklightPrefs

    // System services are nullable on purpose: a denied service_manager
    // find (sepolicy) yields null, and this service shares its process
    // with the HTSR toggle — it must degrade, never crash-loop it.
    private var usageStats: UsageStatsManager? = null
    private var audioManager: AudioManager? = null
    private var telephony: TelephonyManager? = null
    private var cameraManager: CameraManager? = null

    private val handler = Handler(Looper.getMainLooper())
    private val engine = LedController.Engine()

    private lateinit var beatThread: HandlerThread
    private lateinit var beatHandler: Handler

    private var ringing = false
    private val camerasInUse = mutableSetOf<String>()
    private var pendingPulse = false
    private var gamePkg: String? = null
    private var musicNow = false

    // Beat-loop state, touched on the beat thread except where noted.
    @Volatile private var beatGen = 0
    private var beatSource: BeatSource? = null
    private var beatColor = 0
    private var beatBaseline = 0f
    private var beatLevel = 0f

    private var visualizer: Visualizer? = null
    private var visualizerDead = false

    private sealed interface BeatSource {
        data class Game(val pkg: String) : BeatSource
        data object Music : BeatSource
    }

    private val poll =
        object : Runnable {
            override fun run() {
                updateForeground()
                handler.postDelayed(this, POLL_MS)
            }
        }

    private val callListener =
        object : PhoneStateListener() {
            @Deprecated("Use registerTelephonyCallback on API 31+")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                val now = state == TelephonyManager.CALL_STATE_RINGING
                if (now == ringing) return
                ringing = now
                if (DEBUG) Log.d(TAG, "ringing=$ringing")
                recompute()
            }
        }

    private val cameraCallback =
        object : CameraManager.AvailabilityCallback() {
            override fun onCameraUnavailable(cameraId: String) {
                if (camerasInUse.add(cameraId)) recompute()
            }

            override fun onCameraAvailable(cameraId: String) {
                if (camerasInUse.remove(cameraId)) recompute()
            }
        }

    override fun onCreate() {
        super.onCreate()
        prefs = BacklightPrefs(this)
        usageStats = getSystemService(UsageStatsManager::class.java)
        audioManager = getSystemService(AudioManager::class.java)
        telephony = getSystemService(TelephonyManager::class.java)
        cameraManager = getSystemService(CameraManager::class.java)
        beatThread = HandlerThread(TAG + "Beat").apply { start() }
        beatHandler = Handler(beatThread.looper)
        telephony?.let { tm ->
            runCatching {
                @Suppress("DEPRECATION")
                tm.listen(callListener, PhoneStateListener.LISTEN_CALL_STATE)
            }.onFailure { e -> Log.w(TAG, "call listener unavailable", e) }
        }
        cameraManager?.let { cm ->
            runCatching { cm.registerAvailabilityCallback(cameraCallback, handler) }
                .onFailure { e -> Log.w(TAG, "camera callbacks unavailable", e) }
        }
        handler.post(poll)
        recompute()
        if (DEBUG) Log.d(TAG, "started")
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        telephony?.let { tm ->
            runCatching {
                @Suppress("DEPRECATION")
                tm.listen(callListener, PhoneStateListener.LISTEN_NONE)
            }
        }
        cameraManager?.let { cm ->
            runCatching { cm.unregisterAvailabilityCallback(cameraCallback) }
        }
        releaseVisualizer()
        beatThread.quitSafely()
        engine.release()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_NOTIFY) {
            intent.getStringExtra(EXTRA_PACKAGE)?.let { pkg ->
                if (DEBUG) Log.d(TAG, "notify: $pkg")
                pendingPulse = true
                recompute()
            }
        } else {
            recompute()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun brightness(): Float = prefs.brightnessFrac()

    private fun audioPlaying(): Boolean =
        runCatching { audioManager?.isMusicActive }.getOrDefault(false) == true

    private fun recompute() {
        if (!prefs.master || !LedController.isAvailable()) {
            pendingPulse = false
            stopBeat()
            engine.stop()
            return
        }
        val color = prefs.color
        when {
            ringing && prefs.callMode == BacklightPrefs.CALL_ALL -> {
                stopBeat()
                engine.play(LedController.Engine.Effect.Blink(color, 300, 300), brightness())
            }
            prefs.cameraEnabled && camerasInUse.isNotEmpty() -> {
                stopBeat()
                engine.play(LedController.Engine.Effect.Steady(color), brightness())
            }
            pendingPulse -> {
                stopBeat()
                engine.play(
                    LedController.Engine.Effect.Blink(color, 250, 250, repeat = 3),
                    brightness(),
                ) { handler.post { pendingPulse = false; recompute() } }
            }
            gamePkg != null && audioPlaying() -> {
                if (!startBeat(BeatSource.Game(gamePkg!!), prefs.gameColor(gamePkg!!, color))) {
                    stopBeat()
                    playGameStatic(color)
                }
            }
            gamePkg != null -> {
                stopBeat()
                playGameStatic(color)
            }
            musicNow -> {
                if (!startBeat(BeatSource.Music, color)) {
                    stopBeat()
                    engine.stop()
                }
            }
            else -> {
                stopBeat()
                engine.stop()
            }
        }
    }

    private fun playGameStatic(color: Int) {
        val pkg = gamePkg ?: return
        val gameColor = prefs.gameColor(pkg, color)
        val effect =
            when (prefs.gameEffect(pkg)) {
                BacklightPrefs.EFFECT_STEADY -> LedController.Engine.Effect.Steady(gameColor)
                BacklightPrefs.EFFECT_BLINK ->
                    LedController.Engine.Effect.Blink(gameColor, 250, 250)
                else -> LedController.Engine.Effect.Breath(gameColor, 1200)
            }
        engine.play(effect, brightness())
    }

    private fun updateForeground() {
        if (!prefs.master) {
            var changed = false
            if (gamePkg != null) {
                gamePkg = null
                changed = true
            }
            if (musicNow) {
                musicNow = false
                changed = true
            }
            if (changed) recompute()
            return
        }
        val fg = currentForegroundPackage()

        var game: String? = null
        if (fg != null && isGameEnabled(fg)) game = fg

        val music = fg != null && prefs.musicApps.contains(fg) && audioPlaying()

        var changed = false
        if (game != gamePkg) {
            gamePkg = game
            if (DEBUG) Log.d(TAG, "game=$game")
            changed = true
        }
        if (music != musicNow) {
            musicNow = music
            if (DEBUG) Log.d(TAG, "music=$music")
            changed = true
        }
        // Continuous engine.play restarts would stutter breath loops, so only
        // recompute on state changes; the beat loop tracks audio itself.
        if (changed) recompute()
    }

    // ---- Beat-reactive audio lighting ----

    /**
     * Starts (or keeps) the 100ms beat loop. No-op when the same source and
     * color are already beating, so the 2s poll never disturbs it. Returns
     * false when capture is dead — caller plays its fallback instead.
     */
    private fun startBeat(source: BeatSource, color: Int): Boolean {
        if (visualizerDead) return false
        if (beatSource == source && beatColor == color && beatGen > 0) return true
        stopBeatLocked()
        beatSource = source
        beatColor = color
        beatBaseline = 0f
        beatLevel = 0f
        beatGen++
        val gen = beatGen
        if (DEBUG) Log.d(TAG, "beat start: $source")
        beatHandler.post { beatTick(gen) }
        return true
    }

    private fun stopBeat() {
        if (beatGen == 0 && beatSource == null) return
        stopBeatLocked()
        beatSource = null
    }

    private fun stopBeatLocked() {
        beatGen++
        beatHandler.removeCallbacksAndMessages(null)
    }

    private fun beatTick(gen: Int) {
        if (gen != beatGen) return
        // Silence reads as zero energy, which correctly drives the LED dark;
        // only API failures (see ensureVisualizer) kill capture for good.
        val energy = sampleEnergy()
        beatBaseline = 0.95f * beatBaseline + 0.05f * energy
        var target = (energy * 1.4f).coerceIn(0f, 1f)
        if (energy > beatBaseline * 1.5f + 0.08f) target = 1f // onset flash
        beatLevel = max(target, beatLevel * 0.88f) // fast attack, slow release
        LedController.setColor(beatColor)
        LedController.setLevel((beatLevel * LedController.maxLevel() * brightness()).toInt())
        beatHandler.postDelayed({ beatTick(gen) }, BEAT_TICK_MS)
    }

    // ---- Observers ----

    private fun isGameEnabled(pkg: String): Boolean {
        val auto =
            runCatching {
                val ai = packageManager.getApplicationInfo(pkg, 0)
                ai.category == ApplicationInfo.CATEGORY_GAME
            }.getOrDefault(false)
        return prefs.isGameEnabled(pkg, auto)
    }

    private fun currentForegroundPackage(): String? {
        val stats = usageStats ?: return null
        val now = System.currentTimeMillis()
        val events = stats.queryEvents(now - QUERY_WINDOW_MS, now)
        val event = UsageEvents.Event()
        var pkg: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            ) {
                pkg = event.packageName
            }
        }
        return pkg
    }

    private fun ensureVisualizer(): Visualizer? {
        visualizer?.let { return it }
        if (visualizerDead) return null
        return runCatching {
                Visualizer(0).apply {
                    enabled = false
                    captureSize = Visualizer.getCaptureSizeRange()[1]
                    enabled = true
                }
            }
            .onSuccess { visualizer = it }
            .onFailure { e ->
                Log.w(TAG, "visualizer unavailable", e)
                visualizerDead = true
            }
            .getOrNull()
    }

    private fun releaseVisualizer() {
        runCatching {
            visualizer?.enabled = false
            visualizer?.release()
        }
        visualizer = null
    }

    /** 0..1 output-mix energy, 0 when the Visualizer yields nothing. */
    private fun sampleEnergy(): Float {
        val vis = ensureVisualizer() ?: return 0f
        return runCatching {
                val fft = ByteArray(vis.captureSize)
                if (vis.getFft(fft) != Visualizer.SUCCESS) return 0f
                var sum = 0.0
                var n = 0
                var i = 2
                while (i + 1 < fft.size) {
                    val re = fft[i].toInt()
                    val im = fft[i + 1].toInt()
                    sum += (re * re + im * im).toDouble()
                    n++
                    i += 2
                }
                if (n == 0) return 0f
                (sum / n / (128.0 * 128.0)).toFloat().coerceIn(0f, 1f)
            }
            .getOrDefault(0f)
    }

    companion object {
        private const val TAG = "BacklightService"
        private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)

        private const val POLL_MS = 2000L
        private const val QUERY_WINDOW_MS = 10_000L
        private const val BEAT_TICK_MS = 100L

        const val ACTION_NOTIFY = "com.xiaomi.settings.lights.NOTIFY"
        const val EXTRA_PACKAGE = "package"

        fun start(context: Context) {
            context.startServiceAsUser(Intent(context, BacklightService::class.java), UserHandle.CURRENT)
        }

        fun notifyPosted(context: Context, pkg: String) {
            context.startServiceAsUser(
                Intent(context, BacklightService::class.java)
                    .setAction(ACTION_NOTIFY)
                    .putExtra(EXTRA_PACKAGE, pkg),
                UserHandle.CURRENT,
            )
        }
    }
}
