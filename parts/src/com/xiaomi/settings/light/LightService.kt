/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.light

import android.app.Service
import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.preference.PreferenceManager
import com.xiaomi.settings.touch.GameState
import java.util.concurrent.Executor
import kotlin.math.sqrt

/**
 * Back light effects service (AW21024 RGB LED).
 *
 * Priority: incoming call > camera in use > notification pulse >
 * game > charging > music visualizer > standalone color.
 */
class LightService : Service() {

    companion object {
        private const val TAG = "LightService"

        // Beat detector tuning: FFT magnitudes run ~0-128+. Sensitive
        // enough for compressed pop (low crest factor), slow release for
        // deep valleys so single colors visibly pump, not just breathe.
        private const val ONSET_FLOOR = 6f
        private const val ONSET_RATIO = 1.3f
        private const val BRIGHT_GAIN = 0.8f
        private const val ATTACK = 0.7f
        private const val RELEASE = 0.06f

        fun isEnabled(context: Context): Boolean =
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("light_enable", false)

        fun start(context: Context) {
            context.startServiceAsUser(
                Intent(context, LightService::class.java),
                android.os.UserHandle.CURRENT,
            )
        }
    }

    private var telephonyManager: TelephonyManager? = null
    private var audioManager: AudioManager? = null
    private var cameraManager: CameraManager? = null
    private lateinit var sharedPreferences: SharedPreferences
    private var mediaSessionManager: MediaSessionManager? = null

    @Volatile private var isRinging = false
    @Volatile private var isCellRinging = false
    @Volatile private var isCameraActive = false
    @Volatile private var isMusicActive = false
    @Volatile private var isGameModeActive = false
    @Volatile private var isCharging = false
    @Volatile private var batteryPct = -1f
    @Volatile private var isServiceRunning = false
    @Volatile private var isNotificationPulsing = false
    @Volatile private var isDynamicNotification = false

    // One-shot registrations at onCreate can fail when a service binder
    // isn't published yet (locked boot) or is selinux-blocked; the
    // periodic runnable retries until each sticks.
    @Volatile private var telephonyRegistered = false
    @Volatile private var cameraRegistered = false

    private val activeCameras = mutableSetOf<String>()

    private var visualizer: Visualizer? = null
    private var isVisualizerActive = false

    private var wakeLock: PowerManager.WakeLock? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { mainHandler.post(it) }
    private val notificationTimeoutRunnable = Runnable {
        Log.i(TAG, "Notification pulse timeout reached")
        isNotificationPulsing = false
        isDynamicNotification = false
        postUpdateLedState()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    private var visualizerHandlerThread: HandlerThread? = null
    private var visualizerHandler: Handler? = null

    private var ledHandlerThread: HandlerThread? = null
    private var ledHandler: Handler? = null

    private val activeMediaControllers = mutableListOf<MediaController>()

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "light_enable",
            "light_brightness",
            "light_music_enable",
            "light_game_mode_enable",
            "light_charging_enable",
            "light_incoming_call_enable",
            "light_incoming_call_color",
            "light_camera_enable",
            "light_camera_color",
            "light_standalone_enable",
            "light_standalone_color",
            "light_notifications_enable",
            "light_notifications_color",
            "light_music_apps",
            "light_music_color",
            "light_game_mode_color",
            "light_game_mode_apps",
            "light_charging_color",
            "light_charging_mode",
            "light_gradient_speed",
            "light_dynamic_notifications_enable" -> {
                Log.i(TAG, "Preference changed: $key")
                if (key == "light_music_enable" || key == "light_music_apps") {
                    checkMusicState()
                }
                // Game list/enable edits apply now instead of waiting for
                // the next 2s tick; the flag must be fresh before the
                // updateLedState posted below runs.
                if (key == "light_game_mode_apps" || key == "light_game_mode_enable") {
                    ledHandler?.post { refreshGameState() }
                }
                postUpdateLedState()
            }
        }
    }

    private val telephonyCallback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            val ringing = state == TelephonyManager.CALL_STATE_RINGING
            if (ringing != isCellRinging) {
                Log.i(TAG, "Call state changed: ringing=$ringing")
                isCellRinging = ringing
                // Effective ringing is reconciled in the periodic runnable
                // (cellular OR audio-mode, the latter catching VoIP).
                postUpdateLedState()
            }
        }
    }

    private val cameraCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            super.onCameraAvailable(cameraId)
            synchronized(cameraOpLock) {
                activeCameras.remove(cameraId)
            }
            recomputeCameraActive()
        }

        override fun onCameraUnavailable(cameraId: String) {
            super.onCameraUnavailable(cameraId)
            val first = synchronized(cameraOpLock) { activeCameras.add(cameraId) && activeCameras.size == 1 }
            if (first) Log.i(TAG, "Camera in use: $cameraId")
            recomputeCameraActive()
        }
    }

    // AppOps camera-use watch: the primary trigger. AvailabilityCallback
    // dispatch to app processes is broken on this cameraservice (registered
    // listener, busy camera, zero events), while AppOps active-state flows
    // through system_server and fires for front and back alike.
    private val cameraOpLock = Any()
    private val cameraOpUsers = mutableSetOf<String>()
    private var appOpsManager: AppOpsManager? = null
    @Volatile private var cameraOpWatching = false

    private val cameraOpListener = AppOpsManager.OnOpActiveChangedListener { op, uid, packageName, active ->
        if (op != AppOpsManager.OPSTR_CAMERA) return@OnOpActiveChangedListener
        val key = "$uid:${packageName ?: "?"}"
        synchronized(cameraOpLock) {
            if (active) cameraOpUsers.add(key) else cameraOpUsers.remove(key)
        }
        if (active) Log.i(TAG, "Camera op active: $key")
        recomputeCameraActive()
    }

    private fun recomputeCameraActive() {
        val active = synchronized(cameraOpLock) { activeCameras.isNotEmpty() || cameraOpUsers.isNotEmpty() }
        if (active != isCameraActive) {
            Log.i(TAG, "Camera active changed from $isCameraActive to $active")
            isCameraActive = active
            postUpdateLedState()
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCurrentlyCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val pct = if (level >= 0 && scale > 0) level * 100 / scale.toFloat() else -1f

            // Battery broadcasts fire on every level/temperature shift;
            // skip the full re-eval unless charging flipped or the level
            // crossed a charging-color band (25-wide).
            if (isCurrentlyCharging == isCharging && pctBand(pct) == pctBand(batteryPct)) {
                batteryPct = pct
                return
            }
            if (isCharging != isCurrentlyCharging) {
                Log.i(TAG, "Battery state changed: charging=$isCurrentlyCharging")
            }

            isCharging = isCurrentlyCharging
            batteryPct = pct
            postUpdateLedState()
        }

        private fun pctBand(pct: Float): Int =
            // Mirrors the charging-color bands in updateLedState exactly
            // (<= boundaries), so a band change always re-evaluates.
            when {
                pct < 0 -> -1
                pct <= 25 -> 0
                pct <= 50 -> 1
                pct <= 75 -> 2
                else -> 3
            }
    }

    private val mediaControllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            Log.i(TAG, "MediaController.Callback: onPlaybackStateChanged state=${state?.state}")
            checkMusicState()
        }
    }

    private val activeSessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        Log.i(TAG, "MediaSessionManager: OnActiveSessionsChangedListener triggered")
        updateMediaControllers(controllers)
    }

    private fun updateMediaControllers(controllers: List<MediaController>?) {
        // unregister from old
        for (controller in activeMediaControllers) {
            controller.unregisterCallback(mediaControllerCallback)
        }
        activeMediaControllers.clear()

        // register to new
        controllers?.forEach { controller ->
            controller.registerCallback(mediaControllerCallback)
            activeMediaControllers.add(controller)
        }
        checkMusicState()
    }

    private fun checkMusicState() {
        checkMusicState(GameState.focusedPackage(this))
    }

    /**
     * @param foreground pre-resolved focused package, or null to look it
     * up. The periodic poller passes its own lookup so a tick costs one
     * `getTasks` binder call instead of two.
     */
    private fun checkMusicState(foreground: String?) {
        val musicEnabled = sharedPreferences.getBoolean("light_music_enable", false)
        if (!musicEnabled) {
            if (isMusicActive) {
                Log.i(TAG, "checkMusicState: Music disabled, but isMusicActive was true")
                isMusicActive = false
                postUpdateLedState()
            }
            return
        }

        val musicApps = sharedPreferences.getStringSet("light_music_apps", emptySet<String>()) ?: emptySet()

        var controllerPlaying = false
        try {
            for (controller in activeMediaControllers) {
                if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                    if (musicApps.contains(controller.packageName)) {
                        controllerPlaying = true
                        Log.i(TAG, "checkMusicState: Valid music playing from ${controller.packageName}")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkMusicState: Error checking media controllers", e)
        }

        // Audio fallback: covers empty-controller cases (listener not yet
        // registered) and, crucially, also clears a stuck-true when
        // playback stops — the old shape could only ever set true.
        var fallbackPlaying = false
        if (!controllerPlaying && activeMediaControllers.isEmpty() &&
            audioManager?.isMusicActive == true
        ) {
            val fg = foreground ?: GameState.focusedPackage(this)
            fallbackPlaying = fg != null && musicApps.contains(fg)
        }

        val playing = controllerPlaying || fallbackPlaying
        if (playing != isMusicActive) {
            Log.i(TAG, "checkMusicState: isMusicActive changed from $isMusicActive to $playing")
            isMusicActive = playing
            postUpdateLedState()
        }
    }

    /**
     * One-shot registrations can fail when a binder isn't published yet
     * (locked boot) or is selinux-blocked. These return whether the
     * callback stuck; the periodic runnable retries until each does.
     * Verbose failure logs live only in the onCreate pass; retries stay
     * silent to avoid a 2s warning loop.
     */
    private fun tryRegisterTelephony(verbose: Boolean = false): Boolean {
        if (telephonyManager == null) telephonyManager = getSystemService(TelephonyManager::class.java)
        return runCatching {
            telephonyManager?.registerTelephonyCallback(mainExecutor, telephonyCallback)
                ?: throw IllegalStateException("telephony service not published yet")
        }.onFailure { e ->
            if (verbose) Log.w(TAG, "Failed to register telephony callback", e)
        }.isSuccess
    }

    private fun tryRegisterCamera(verbose: Boolean = false): Boolean {
        if (cameraManager == null) cameraManager = getSystemService(CameraManager::class.java)
        return runCatching {
            cameraManager?.registerAvailabilityCallback(mainExecutor, cameraCallback)
                ?: throw IllegalStateException("camera service not published yet")
        }.onFailure { e ->
            if (verbose) Log.w(TAG, "Failed to register camera callback", e)
        }.isSuccess
    }

    private fun tryWatchCameraOps(verbose: Boolean = false): Boolean {
        if (appOpsManager == null) appOpsManager = getSystemService(AppOpsManager::class.java)
        return runCatching {
            (appOpsManager ?: throw IllegalStateException("appops not published yet"))
                .startWatchingActive(intArrayOf(AppOpsManager.OP_CAMERA), cameraOpListener)
        }.onFailure { e ->
            if (verbose) Log.w(TAG, "Failed to watch camera appops", e)
        }.isSuccess
    }

    private fun postUpdateLedState() {
        ledHandler?.post { updateLedState() }
    }

    override fun onCreate() {
        super.onCreate()
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        // Typed getters return null instead of throwing when a service is
        // not published yet (locked-boot ordering): degrade, never crash,
        // or the whole persistent parts process goes down with us.
        telephonyManager = getSystemService(TelephonyManager::class.java)
        audioManager = getSystemService(AudioManager::class.java)
        cameraManager = getSystemService(CameraManager::class.java)
        mediaSessionManager = getSystemService(MediaSessionManager::class.java)
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LightService:NotificationPulse")

        telephonyRegistered = tryRegisterTelephony(verbose = true)
        cameraRegistered = tryRegisterCamera(verbose = true)
        cameraOpWatching = tryWatchCameraOps(verbose = true)

        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        sharedPreferences.registerOnSharedPreferenceChangeListener(prefListener)
        visualizerHandlerThread = HandlerThread("VisualizerThread").apply { start() }
        visualizerHandler = Handler(visualizerHandlerThread!!.looper)

        ledHandlerThread = HandlerThread("LedHandlerThread").apply { start() }
        ledHandler = Handler(ledHandlerThread!!.looper)

        isServiceRunning = true

        try {
            val sessions = mediaSessionManager?.getActiveSessions(null)
            updateMediaControllers(sessions)
            mediaSessionManager?.addOnActiveSessionsChangedListener(activeSessionsListener, null)
            Log.i(TAG, "Registered MediaSessionManager listener")
        } catch (e: SecurityException) {
            Log.w(TAG, "Failed to register MediaSessionManager listener, missing permissions", e)
        } catch (e: Exception) {
            // Locked-boot ordering: the session service binder is not
            // published yet (NPE inside getActiveSessions). Drop the
            // manager so music viz degrades instead of crashing the
            // whole persistent parts process (HTSR included).
            Log.w(TAG, "MediaSessionManager not ready, music effects off", e)
            mediaSessionManager = null
        }

        ledHandler?.postDelayed(backgroundStateRunnable, 2000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_PULSE_NOTIFICATION") {
            Log.i(TAG, "Received ACTION_PULSE_NOTIFICATION")
            isDynamicNotification = intent.getBooleanExtra("dynamic", false)
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(6000)
            }
            // Always refresh (not just on the false->true transition) so that
            // dynamic notifications re-roll a fresh top/bottom color pair on
            // every new notification, even while still pulsing.
            isNotificationPulsing = true
            postUpdateLedState()
            mainHandler.removeCallbacks(notificationTimeoutRunnable)
            mainHandler.postDelayed(notificationTimeoutRunnable, 5000)
        } else {
            postUpdateLedState()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy: stopping service")
        isServiceRunning = false
        runCatching { telephonyManager?.unregisterTelephonyCallback(telephonyCallback) }
        runCatching { cameraManager?.unregisterAvailabilityCallback(cameraCallback) }
        runCatching { appOpsManager?.stopWatchingActive(cameraOpListener) }
        unregisterReceiver(batteryReceiver)
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(prefListener)

        try {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(activeSessionsListener)
        } catch (e: Exception) {
            Log.w(TAG, "Error removing MediaSessionManager listener", e)
        }
        updateMediaControllers(emptyList())

        visualizerHandlerThread?.quitSafely()
        ledHandlerThread?.quitSafely()
        stopVisualizer()
        LedManager.turnOff()
        synchronized(cameraOpLock) {
            activeCameras.clear()
            cameraOpUsers.clear()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Game-mode flag re-eval (no foreground lookup of its own) used by
     * the poller, which already resolved the focused package.
     * @return true when the flag changed
     */
    private fun refreshGameState(gameEnabled: Boolean, foreground: String?): Boolean {
        if (gameEnabled) {
            val gameApps = sharedPreferences.getStringSet("light_game_mode_apps", emptySet<String>()) ?: emptySet()
            val currentlyInGame = foreground != null && gameApps.contains(foreground)
            if (currentlyInGame != isGameModeActive) {
                Log.i(TAG, "Game mode active changed from $isGameModeActive to $currentlyInGame")
                isGameModeActive = currentlyInGame
                return true
            }
        } else if (isGameModeActive) {
            Log.i(TAG, "Game mode disabled, disabling game mode state")
            isGameModeActive = false
            return true
        }
        return false
    }

    /** Self-resolving variant: called from pref edits for instant apply. */
    private fun refreshGameState() {
        val gameEnabled = sharedPreferences.getBoolean("light_game_mode_enable", false)
        val foreground =
            if (gameEnabled) GameState.focusedPackage(this) else null
        refreshGameState(gameEnabled, foreground)
    }

    private val backgroundStateRunnable = object : Runnable {
        override fun run() {
            if (!isServiceRunning) return

            var stateChanged = false

            // Late registration retries (see helpers): one-shot onCreate
            // registration can fail on locked boot; retry until each sticks.
            if (!telephonyRegistered) {
                telephonyRegistered = tryRegisterTelephony()
                if (telephonyRegistered) Log.i(TAG, "Telephony callback late-registered")
            }
            if (!cameraRegistered) {
                cameraRegistered = tryRegisterCamera()
                if (cameraRegistered) Log.i(TAG, "Camera callback late-registered")
            }
            if (!cameraOpWatching) {
                cameraOpWatching = tryWatchCameraOps()
                if (cameraOpWatching) Log.i(TAG, "Camera appops late-watching")
            }

            // Effective ringing: cellular TelephonyCallback OR audio-mode
            // RINGTONE, the latter catching VoIP (WhatsApp etc.) which never
            // drives TelephonyManager call state.
            val ringNow = isCellRinging || audioManager?.mode == AudioManager.MODE_RINGTONE
            if (ringNow != isRinging) {
                Log.i(TAG, "Ringing changed from $isRinging to $ringNow")
                isRinging = ringNow
                stateChanged = true
            }

            // Check Game Mode. The focused package is resolved once per
            // tick and shared with the music check below — but only when
            // something actually needs it (game on, or music on with live
            // audio for the fallback).
            val gameEnabled = sharedPreferences.getBoolean("light_game_mode_enable", false)
            val musicEnabled = sharedPreferences.getBoolean("light_music_enable", false)
            val foreground =
                if (gameEnabled || (musicEnabled && audioManager?.isMusicActive == true)) {
                    GameState.focusedPackage(this@LightService)
                } else {
                    null
                }
            if (refreshGameState(gameEnabled, foreground)) stateChanged = true

            // Late retry: if the session binder wasn't published at onCreate
            // (locked-boot ordering) the listener never registered. Re-try
            // here so rhythmic effects recover without a reboot.
            if (mediaSessionManager == null) {
                val mgr = getSystemService(MediaSessionManager::class.java)
                val ok =
                    mgr != null &&
                        runCatching {
                            updateMediaControllers(mgr.getActiveSessions(null))
                            mgr.addOnActiveSessionsChangedListener(activeSessionsListener, null)
                        }.isSuccess
                mediaSessionManager = mgr?.takeIf { ok }
                if (ok) Log.i(TAG, "MediaSessionManager late-registered")
            }

            // Music state (controllers or audio fallback, both directions).
            // Self-posts an LED update on change, so no stateChanged here.
            checkMusicState(foreground)

            if (stateChanged) {
                updateLedState() // since this runnable runs on ledHandler
            }

            ledHandler?.postDelayed(this, 2000)
        }
    }

    private val availableColors = arrayOf("ff0000", "ff7f00", "ffff00", "00ff00", "00ffff", "0000ff", "800080")
    private var lastColorChangeTime = 0L
    @Volatile private var lastBeatColorHex = ""

    // Beat detector state: bass energy vs adaptive floor (kick/onset),
    // plus smoothed level (fast attack, slow release). Reset per session.
    @Volatile private var bassFloor = 0f
    @Volatile private var smoothLevel = 0f

    // Visualizer color source: music pref by default, game pref while a
    // game is focused and audio is playing (game branch sets these).
    @Volatile private var beatUseGameColor = false
    @Volatile private var beatGameColorHex = "gradient"

    // Latest capture state handed to the visualizer thread. One
    // pre-allocated runnable is reused (the old shape allocated a fresh
    // lambda + captures on every FFT callback) and the pending flag
    // coalesces bursts into a single post — the same at-most-one-queued
    // behavior the old removeCallbacksAndMessages gave us.
    @Volatile private var pendingOnset = false
    @Volatile private var pendingBrightness = 0
    @Volatile private var vizPending = false

    /**
     * Bumped on every start/stop of the visualizer. The FFT callback runs
     * on the audio capture thread, so one can be in flight while
     * stopVisualizer() is tearing things down and will happily post a
     * vizUpdate *after* removeCallbacks(). That late update used to call
     * setVisualizerActive() -> cancelSweep(), which killed whichever
     * gradient had just been started (notifications, charging), so the
     * strip went dark when a notification arrived. A queued update is
     * tagged with the generation it was produced under and dropped if the
     * generation moved on.
     */
    @Volatile private var vizGeneration = 0
    @Volatile private var pendingVizGeneration = 0

    private val vizUpdate = Runnable {
        vizPending = false
        if (pendingVizGeneration != vizGeneration || !isVisualizerActive) return@Runnable
        val onset = pendingOnset
        val brightness = pendingBrightness
        LedManager.setVisualizerActive()
        // Hue follows beats, not a timer: color steps only on onsets
        // (musical), at most ~7Hz. Clock and prefs stay out of the hot
        // path otherwise.
        if (onset) {
            val now = System.currentTimeMillis()
            if (now - lastColorChangeTime > 150) {
                val beatPref = if (beatUseGameColor) beatGameColorHex
                else sharedPreferences.getString("light_music_color", "gradient")
                    ?: "gradient"
                val beatColor =
                    if (beatPref == "gradient") availableColors.random()
                    else beatPref
                if (beatColor != lastBeatColorHex) {
                    LedManager.setVisualizerColor(beatColor)
                    lastBeatColorHex = beatColor
                }
                lastColorChangeTime = now
            }
        }
        LedManager.setVisualizerBrightness(brightness)
    }

    private fun startVisualizer() {
        if (isVisualizerActive) return
        Log.i(TAG, "startVisualizer")
        vizGeneration++
        lastBeatColorHex = ""
        bassFloor = 0f
        smoothLevel = 0f
        try {
            visualizer = Visualizer(0)
            visualizer?.captureSize = Visualizer.getCaptureSizeRange()[1]
            visualizer?.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}

                override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                    if (fft == null || fft.isEmpty()) return

                    // Bass-weighted energy (low bins carry kick/bass) plus
                    // overall peak for the noise floor estimate.
                    val bins = fft.size / 2
                    val bassBins = minOf(8, bins)
                    var bassSum = 0f
                    for (i in 0 until bassBins) {
                        val re = fft[i * 2].toFloat()
                        val im = fft[i * 2 + 1].toFloat()
                        bassSum += sqrt(re * re + im * im)
                    }
                    val bass = if (bassBins > 0) bassSum / bassBins else 0f

                    // Onset (beat): energy clearly above the adaptive floor.
                    val onset = bass > ONSET_FLOOR && bass > bassFloor * ONSET_RATIO
                    bassFloor += (bass - bassFloor) * (if (bass > bassFloor) 0.25f else 0.05f)

                    // Level: fast attack so kicks pop, slow release so it
                    // breathes instead of flickering.
                    val target = (bass * BRIGHT_GAIN).coerceIn(0f, 80f)
                    smoothLevel += (target - smoothLevel) * (if (target > smoothLevel) ATTACK else RELEASE)
                    val brightness = if (onset) 80 else smoothLevel.toInt()

                    pendingOnset = onset
                    pendingBrightness = brightness
                    val vh = visualizerHandler
                    val gen = vizGeneration
                    if (vh != null && !vizPending && gen == vizGeneration) {
                        pendingVizGeneration = gen
                        vizPending = true
                        vh.post(vizUpdate)
                    }
                }
            }, Visualizer.getMaxCaptureRate() / 2, false, true)
            visualizer?.enabled = true
            isVisualizerActive = true
        } catch (e: Exception) {
            Log.w(TAG, "startVisualizer failed", e)
            isVisualizerActive = false
        }
    }

    private fun stopVisualizer() {
        // Invalidate first, and do it even when already inactive: an FFT
        // callback racing the teardown can post after removeCallbacks(),
        // and that update must be recognised as stale.
        vizGeneration++
        if (!isVisualizerActive) return
        Log.i(TAG, "stopVisualizer")
        // Drop any queued vizUpdate: it would re-power the LEDs through
        // setVisualizerActive right after we turned everything off.
        visualizerHandler?.removeCallbacks(vizUpdate)
        vizPending = false
        try {
            visualizer?.enabled = false
            visualizer?.release()
            visualizer = null
        } catch (e: Exception) {
            Log.w(TAG, "stopVisualizer failed", e)
        }
        isVisualizerActive = false
    }

    private fun stopAllEffects() {
        stopVisualizer()
        LedManager.turnOff()
    }

    /**
     * Shared gradient-vs-solid dispatch: "gradient" runs the hue sweep,
     * anything else calls the solid effect. Keeps the six priority
     * branches below from duplicating it.
     */
    private fun applyEffectColor(key: String, default: String, solid: (String) -> Unit) {
        val color = sharedPreferences.getString(key, default) ?: default
        if (color == "gradient") LedManager.setGradientSweep(true)
        else solid(color)
    }

    private fun updateLedState() {
        val masterEnabled = sharedPreferences.getBoolean("light_enable", false)
        if (!masterEnabled) {
            Log.d(TAG, "updateLedState: Master toggle disabled, stopping all effects")
            stopAllEffects()
            return
        }

        LedManager.brightnessScale = sharedPreferences.getInt("light_brightness", 100) / 100f
        LedManager.gradientSpeed = sharedPreferences.getInt("light_gradient_speed", 50)

        // Priority 1: Incoming Call
        if (isRinging) {
            val callEnabled = sharedPreferences.getBoolean("light_incoming_call_enable", false)
            if (callEnabled) {
                Log.d(TAG, "updateLedState: Incoming call priority")
                stopAllEffects()
                applyEffectColor("light_incoming_call_color", "ff0000") { color ->
                    LedManager.setBlink(color, 1000, 1000, 1000, 1000, true)
                }
                return
            }
        }

        // Priority 2: Camera in use
        if (isCameraActive) {
            val cameraEnabled = sharedPreferences.getBoolean("light_camera_enable", false)
            if (cameraEnabled) {
                Log.d(TAG, "updateLedState: Camera priority")
                stopAllEffects()
                applyEffectColor("light_camera_color", "ff0000", LedManager::setStaticColor)
                return
            }
        }

        // Priority 3: Notification Pulse
        if (isNotificationPulsing) {
            if (isDynamicNotification) {
                val dynEnabled = sharedPreferences.getBoolean("light_dynamic_notifications_enable", false)
                if (dynEnabled) {
                    Log.d(TAG, "updateLedState: Dynamic notification pulse priority")
                    stopAllEffects()
                    LedManager.setDynamicBlink(1000, 1000, 1000, 1000, true)
                    return
                }
            } else {
                val notifEnabled = sharedPreferences.getBoolean("light_notifications_enable", false)
                if (notifEnabled) {
                    Log.d(TAG, "updateLedState: Notification pulse priority")
                    stopAllEffects()
                    applyEffectColor("light_notifications_color", "ff0000") { color ->
                        LedManager.setBlink(color, 1000, 1000, 1000, 1000, true)
                    }
                    return
                }
            }
        }

        // Priority 4: Game Mode (beat-reactive while audio plays)
        if (isGameModeActive) {
            val gameEnabled = sharedPreferences.getBoolean("light_game_mode_enable", false)
            if (gameEnabled) {
                val color = sharedPreferences.getString("light_game_mode_color", "gradient") ?: "gradient"
                if (audioManager?.isMusicActive == true) {
                    // Game BGM/SFX: visualizer drives brightness with the
                    // game hue (gradient cycles per beat, like music).
                    Log.d(TAG, "updateLedState: Game beat priority")
                    beatUseGameColor = true
                    beatGameColorHex = color
                    if (!isVisualizerActive) {
                        LedManager.turnOff()
                        startVisualizer()
                    }
                } else {
                    beatUseGameColor = false
                    Log.d(TAG, "updateLedState: Game mode priority")
                    stopAllEffects()
                    applyEffectColor("light_game_mode_color", "gradient", LedManager::setStaticColor)
                }
                return
            }
        }

        // Priority 5: Music Visualizer (active playback wins over ambient charging)
        if (isMusicActive) {
            val musicEnabled = sharedPreferences.getBoolean("light_music_enable", false)
            if (musicEnabled) {
                Log.d(TAG, "updateLedState: Music visualizer priority")
                beatUseGameColor = false
                // Stop gradient/blink if standalone or game was previously active
                if (!isVisualizerActive) {
                    LedManager.turnOff()
                    startVisualizer()
                }
                return
            }
        }

        // Priority 6: Charging (pulsing picked color, or battery-level bands)
        if (isCharging) {
            val chargingEnabled = sharedPreferences.getBoolean("light_charging_enable", false)
            if (chargingEnabled) {
                Log.d(TAG, "updateLedState: Charging priority")
                stopAllEffects()
                val mode = sharedPreferences.getString("light_charging_mode", "level") ?: "level"
                val color = if (mode == "custom") {
                    sharedPreferences.getString("light_charging_color", "00ff00") ?: "00ff00"
                } else {
                    // Battery bands: 1-25 red, 26-50 orange, 51-75 yellow, 76-100 green.
                    when {
                        batteryPct < 0 -> "00ff00"
                        batteryPct <= 25 -> "ff0000"
                        batteryPct <= 50 -> "ff7f00"
                        batteryPct <= 75 -> "ffff00"
                        else -> "00ff00"
                    }
                }
                if (color == "gradient") LedManager.setGradientSweep(true)
                else LedManager.setBlink(color, 2000, 1000, 2000, 1000, true)
                return
            }
        }

        // Priority 7: Standalone Color
        val standaloneEnabled = sharedPreferences.getBoolean("light_standalone_enable", false)
        if (standaloneEnabled) {
            Log.d(TAG, "updateLedState: Standalone color priority")
            // Stop visualizer if music mode was previously active
            stopAllEffects()
            applyEffectColor("light_standalone_color", "ff0000", LedManager::setStaticColor)
            return
        }

        // Default: everything off
        stopAllEffects()
    }
}
