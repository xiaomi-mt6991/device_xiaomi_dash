/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.light

import android.graphics.Color
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.xiaomi.settings.utils.FileUtils
import java.io.FileOutputStream

/**
 * Driver for the AW21024 RGB LED (back light effects).
 *
 * Nodes: hwen / run / repeat / period / brightness / rgbcolor / gradient.
 * runMode 1 = always-on, 2 = blink/breathe.
 *
 * Gradient ("rainbow") is a software hue sweep, not the driver mode:
 * the driver's built-in gradient neither cycles the full wheel nor
 * honors speed, and rgbcolor writes only land while run is active
 * (driver validates run/timer state at write time). The sweep ticks on
 * its own thread at [gradientSpeed] rate through runMode 1.
 * Idempotent: repeat calls with identical parameters are no-ops so the
 * 2s state poller and preference listeners never re-trigger the hardware.
 * Thread-safe: every entry point synchronizes on the instance because the
 * sweep thread, the LED handler thread and the visualizer thread all
 * drive the same nodes/state.
 */
object LedManager {

    private const val TAG = "LedManager"

    private const val LED_PATH = "/sys/class/leds/aw21024_led"
    private const val HWEN_NODE = "$LED_PATH/hwen"
    private const val RUN_NODE = "$LED_PATH/run"
    private const val REPEAT_NODE = "$LED_PATH/repeat"
    private const val PERIOD_NODE = "$LED_PATH/period"
    private const val BRIGHTNESS_NODE = "$LED_PATH/brightness"
    private const val RGBCOLOR_NODE = "$LED_PATH/rgbcolor"
    private const val GRADIENT_NODE = "$LED_PATH/gradient"

    private const val BASE_BRIGHTNESS = 80

    /**
     * White-point trim: full 0xFFFFFF renders pinkish on this strip
     * (hot red channel), so white is pulled toward cyan. First
     * calibration; adjust if still off.
     */
    private const val WHITE_CAL = "e0ffff"
    private fun normalize(hex: String): String =
        if (hex.equals("ffffff", ignoreCase = true)) WHITE_CAL else hex

    /** 0.1..1.0, driven by the light_brightness preference. */
    @Volatile
    var brightnessScale: Float = 1f
    @Volatile
    var gradientSpeed: Int = 50

    private fun scaledBrightness(): Int = (BASE_BRIGHTNESS * brightnessScale).toInt().coerceIn(1, BASE_BRIGHTNESS)

    /**
     * Minimal writer for the hot paths (sweep tick, visualizer).
     *
     * FileUtils.writeLine builds a BufferedWriter per call, which allocates
     * an 8 KiB char buffer every time. At ~30 Hz across two channels that
     * is steady GC churn on the sweep thread, which is exactly what makes
     * an animation look choppy. A raw FileOutputStream keeps it to one
     * small byte array per write.
     */
    private fun writeFast(node: String, value: String) {
        runCatching { FileOutputStream(node).use { it.write(value.toByteArray()) } }
            .onFailure { Log.w(TAG, "write $node failed: ${it.message}") }
    }

    private var isActive = false
    private var visualizerSteady = false
    private var lastVizWrite: Pair<Int, Float>? = null
    private var lastRunMode = -1
    private var lastColorHex = ""
    private var lastGradientSpeed = -1
    private var lastRiseMs = -1
    private var lastOnMs = -1
    private var lastFallMs = -1
    private var lastOffMs = -1
    private var lastRepeat = false
    private var lastOffTimeMs = 0L

    /**
     * Brightness scale folded into the static/blink dedupe keys: the
     * node write happens inside those paths, so a brightness-slider
     * change with an otherwise identical effect must not early-return
     * or the new scale would never reach the hardware.
     */
    private var lastScale = -1f

    @Synchronized
    private fun powerOnIfNeeded(runMode: Int) {
        if (!isActive) {
            val now = System.currentTimeMillis()
            val elapsed = now - lastOffTimeMs
            if (elapsed < 15) {
                Thread.sleep(15 - elapsed)
            }
            Log.i(TAG, "powerOnIfNeeded: Turning ON with runMode=$runMode")
            FileUtils.writeLine(HWEN_NODE, "1")
            isActive = true
            FileUtils.writeLine(RUN_NODE, runMode.toString())
            lastRunMode = runMode
        } else if (lastRunMode != runMode) {
            Log.i(TAG, "powerOnIfNeeded: Changing runMode from $lastRunMode to $runMode")
            FileUtils.writeLine(RUN_NODE, "0")
            FileUtils.writeLine(HWEN_NODE, "0")
            Thread.sleep(15) // Settle time
            FileUtils.writeLine(HWEN_NODE, "1")
            FileUtils.writeLine(RUN_NODE, runMode.toString())
            lastRunMode = runMode
        }
    }

    private val dynamicColors = arrayOf("ff0000", "ff7f00", "ffff00", "00ff00", "00ffff", "0000ff", "800080", "ff00ff")

    @Synchronized
    private fun setDeviceColor(colorHex: String) {
        writeRgbPair(colorHex)
        FileUtils.writeLine(BRIGHTNESS_NODE, scaledBrightness().toString())
    }

    @Synchronized
    private fun setDeviceDualColor(topColorHex: String, bottomColorHex: String) {
        FileUtils.writeLine(RGBCOLOR_NODE, "0x04 0x${normalize(bottomColorHex)}")
        FileUtils.writeLine(RGBCOLOR_NODE, "0x03 0x${normalize(topColorHex)}")
        FileUtils.writeLine(BRIGHTNESS_NODE, scaledBrightness().toString())
    }

    // Software hue sweep (see class doc): full 0-360 wheel at
    // [gradientSpeed] rate. Higher slider = shorter full cycle.
    private val sweepThread = HandlerThread("LedSweep").apply { start() }
    private val sweepHandler = Handler(sweepThread.looper)
    private var sweepActive = false
    private var sweepHue = 0f
    private var lastSweepTickMs = 0L
    /**
     * Brightness scale the hardware was last told about. The sweep writes
     * brightness only when this changes, not on every tick: brightness is
     * constant across a hue rotation, and re-writing it at tick rate both
     * wastes a sysfs write and stomps the music visualizer's beat-driven
     * brightness whenever the two overlap.
     */
    private var sweepLastScale = -1f
    private val sweepTick =
        object : Runnable {
            @Synchronized
            override fun run() {
                if (!sweepActive) return

                // Advance by real elapsed time, not the nominal tick, so a
                // busy CPU delays a frame instead of stretching the cycle.
                val now = android.os.SystemClock.uptimeMillis()
                val delta =
                    if (lastSweepTickMs == 0L) SWEEP_TICK_MS
                    else (now - lastSweepTickMs).coerceIn(1L, SWEEP_TICK_MS * 4)
                lastSweepTickMs = now

                sweepHue = (sweepHue + 360f * delta / sweepCycleMs()) % 360f
                val hex =
                    Integer.toHexString(
                        Color.HSVToColor(floatArrayOf(sweepHue, 1f, 1f)) and 0xFFFFFF,
                    ).padStart(6, '0')
                writeRgbPair(hex)

                if (sweepLastScale != brightnessScale) {
                    sweepLastScale = brightnessScale
                    writeFast(BRIGHTNESS_NODE, scaledBrightness().toString())
                }

                sweepHandler.postDelayed(this, SWEEP_TICK_MS)
            }
        }

    private fun sweepCycleMs(): Long = ((105 - gradientSpeed.coerceIn(1, 100)) * 100L).coerceAtLeast(500L)

    /**
     * ~30Hz. The old 100ms (10Hz) was visibly steppy next to Game mode,
     * which updates at the audio capture rate and lets the driver
     * interpolate. 33ms matches that cadence closely enough that the two
     * modes look consistent.
     */
    private const val SWEEP_TICK_MS = 33L

    @Synchronized
    private fun cancelSweep() {
        sweepActive = false
        visualizerSteady = false
        sweepHandler.removeCallbacks(sweepTick)
    }

    @Synchronized
    fun turnOff() {
        if (!isActive) return
        Log.i(TAG, "turnOff: Turning OFF LEDs")
        cancelSweep()
        lastVizWrite = null
        FileUtils.writeLine(RUN_NODE, "0")
        FileUtils.writeLine(GRADIENT_NODE, "0")
        FileUtils.writeLine(REPEAT_NODE, "0")
        FileUtils.writeLine(HWEN_NODE, "0")
        isActive = false
        lastRunMode = -1
        lastColorHex = ""
        lastOffTimeMs = System.currentTimeMillis()
    }

    @Synchronized
    fun setVisualizerActive() {
        // Steady state: the FFT path calls this every tick (~10Hz);
        // only the transition does work.
        if (isActive && lastRunMode == 1 && visualizerSteady) return
        Log.i(TAG, "setVisualizerActive")
        cancelSweep()
        powerOnIfNeeded(1) // 1 = always-on (dynamic brightness)
        FileUtils.writeLine(GRADIENT_NODE, "0")
        visualizerSteady = true
    }

    @Synchronized
    fun setVisualizerBrightness(level: Int) {
        // FFT rate (~10Hz): skip identical rewrites (open+write+close).
        // The scale is folded into the key since it can change under us.
        val key = level to brightnessScale
        if (key == lastVizWrite) return
        lastVizWrite = key
        writeFast(BRIGHTNESS_NODE, (level * brightnessScale).toInt().coerceIn(0, BASE_BRIGHTNESS).toString())
    }

    @Synchronized
    fun setVisualizerColor(colorHex: String) {
        writeRgbPair(colorHex)
    }

    @Synchronized
    private fun writeRgbPair(colorHex: String) {
        val hex = normalize(colorHex)
        writeFast(RGBCOLOR_NODE, "0x04 0x$hex")
        writeFast(RGBCOLOR_NODE, "0x03 0x$hex")
    }

    @Synchronized
    fun setStaticColor(colorHex: String) {
        if (isActive && lastRunMode == 1 && lastColorHex == colorHex && lastScale == brightnessScale) {
            return
        }
        Log.i(TAG, "setStaticColor: color=$colorHex")
        cancelSweep()
        powerOnIfNeeded(1) // 1 = always-on
        setDeviceColor(colorHex)
        lastColorHex = colorHex
        lastScale = brightnessScale
    }

    @Synchronized
    fun setBlink(colorHex: String, riseMs: Int, onMs: Int, fallMs: Int, offMs: Int, repeat: Boolean) {
        if (isActive && lastRunMode == 2 && lastColorHex == colorHex &&
            lastRiseMs == riseMs && lastOnMs == onMs && lastFallMs == fallMs &&
            lastOffMs == offMs && lastRepeat == repeat && lastScale == brightnessScale) {
            return
        }
        Log.i(TAG, "setBlink: color=$colorHex, repeat=$repeat")
        cancelSweep()
        powerOnIfNeeded(2) // 2 = blink/breathe
        FileUtils.writeLine(REPEAT_NODE, if (repeat) "1" else "0")
        FileUtils.writeLine(PERIOD_NODE, "$riseMs $onMs $fallMs $offMs")
        setDeviceColor(colorHex)

        lastColorHex = colorHex
        lastRiseMs = riseMs
        lastOnMs = onMs
        lastFallMs = fallMs
        lastOffMs = offMs
        lastRepeat = repeat
        lastScale = brightnessScale
    }

    @Synchronized
    fun setDynamicBlink(riseMs: Int, onMs: Int, fallMs: Int, offMs: Int, repeat: Boolean) {
        Log.i(TAG, "setDynamicBlink")
        cancelSweep()
        powerOnIfNeeded(2) // 2 = blink/breathe
        FileUtils.writeLine(REPEAT_NODE, if (repeat) "1" else "0")
        FileUtils.writeLine(PERIOD_NODE, "$riseMs $onMs $fallMs $offMs")

        val topColor = dynamicColors.random()
        var bottomColor = dynamicColors.random()
        while (bottomColor == topColor) {
            bottomColor = dynamicColors.random()
        }
        setDeviceDualColor(topColor, bottomColor)

        // "dynamic" is never a real hex value, so the equality checks in
        // setBlink/setStaticColor never accidentally short-circuit this mode,
        // and a fresh random pair is written on every call.
        lastColorHex = "dynamic"
        lastRiseMs = riseMs
        lastOnMs = onMs
        lastFallMs = fallMs
        lastOffMs = offMs
        lastRepeat = repeat
    }

    @Synchronized
    fun setGradientSweep(enable: Boolean) {
        if (!enable) {
            turnOff()
            return
        }
        if (sweepActive && lastGradientSpeed == gradientSpeed) {
            return
        }
        Log.i(TAG, "setGradientSweep: speed=$gradientSpeed")
        cancelSweep()
        sweepActive = true
        // Force the first tick to publish brightness: sweepLastScale is
        // compared against it, so leaving it equal would skip the write
        // and the strip would stay at whatever the previous effect left.
        sweepLastScale = -1f
        lastSweepTickMs = 0L
        powerOnIfNeeded(1) // always-on base; hue steps do the sweep
        FileUtils.writeLine(GRADIENT_NODE, "0")
        FileUtils.writeLine(REPEAT_NODE, "0")
        lastGradientSpeed = gradientSpeed
        lastColorHex = "sweep"
        sweepHandler.post(sweepTick)
    }
}
