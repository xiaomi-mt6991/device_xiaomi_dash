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
    var gradientSpeed: Int = 50

    private fun scaledBrightness(): Int = (BASE_BRIGHTNESS * brightnessScale).toInt().coerceIn(1, BASE_BRIGHTNESS)

    private var isActive = false
    private var visualizerSteady = false
    private var lastRunMode = -1
    private var lastColorHex = ""
    private var lastGradientSpeed = -1
    private var lastRiseMs = -1
    private var lastOnMs = -1
    private var lastFallMs = -1
    private var lastOffMs = -1
    private var lastRepeat = false
    private var lastOffTimeMs = 0L

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

    private fun setDeviceColor(colorHex: String) {
        val hex = normalize(colorHex)
        FileUtils.writeLine(RGBCOLOR_NODE, "0x04 0x$hex")
        FileUtils.writeLine(RGBCOLOR_NODE, "0x03 0x$hex")
        FileUtils.writeLine(BRIGHTNESS_NODE, scaledBrightness().toString())
    }

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
    private val sweepTick =
        object : Runnable {
            override fun run() {
                if (!sweepActive) return
                sweepHue = (sweepHue + 360f * SWEEP_TICK_MS / sweepCycleMs()) % 360f
                val hex =
                    Integer.toHexString(
                        Color.HSVToColor(floatArrayOf(sweepHue, 1f, 1f)) and 0xFFFFFF,
                    ).padStart(6, '0')
                FileUtils.writeLine(RGBCOLOR_NODE, "0x04 0x$hex")
                FileUtils.writeLine(RGBCOLOR_NODE, "0x03 0x$hex")
                FileUtils.writeLine(BRIGHTNESS_NODE, scaledBrightness().toString())
                sweepHandler.postDelayed(this, SWEEP_TICK_MS)
            }
        }

    private fun sweepCycleMs(): Long = ((105 - gradientSpeed.coerceIn(1, 100)) * 100L).coerceAtLeast(500L)

    private const val SWEEP_TICK_MS = 100L

    private fun cancelSweep() {
        sweepActive = false
        visualizerSteady = false
        sweepHandler.removeCallbacks(sweepTick)
    }

    fun turnOff() {
        if (!isActive) return
        Log.i(TAG, "turnOff: Turning OFF LEDs")
        cancelSweep()
        FileUtils.writeLine(RUN_NODE, "0")
        FileUtils.writeLine(GRADIENT_NODE, "0")
        FileUtils.writeLine(REPEAT_NODE, "0")
        FileUtils.writeLine(HWEN_NODE, "0")
        isActive = false
        lastRunMode = -1
        lastColorHex = ""
        lastOffTimeMs = System.currentTimeMillis()
    }

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

    fun setVisualizerBrightness(level: Int) {
        FileUtils.writeLine(BRIGHTNESS_NODE, (level * brightnessScale).toInt().coerceIn(0, BASE_BRIGHTNESS).toString())
    }

    fun setVisualizerColor(colorHex: String) {
        val hex = normalize(colorHex)
        FileUtils.writeLine(RGBCOLOR_NODE, "0x04 0x$hex")
        FileUtils.writeLine(RGBCOLOR_NODE, "0x03 0x$hex")
    }

    fun setStaticColor(colorHex: String) {
        if (isActive && lastRunMode == 1 && lastColorHex == colorHex) {
            return
        }
        Log.i(TAG, "setStaticColor: color=$colorHex")
        cancelSweep()
        powerOnIfNeeded(1) // 1 = always-on
        setDeviceColor(colorHex)
        lastColorHex = colorHex
    }

    fun setBlink(colorHex: String, riseMs: Int, onMs: Int, fallMs: Int, offMs: Int, repeat: Boolean) {
        if (isActive && lastRunMode == 2 && lastColorHex == colorHex &&
            lastRiseMs == riseMs && lastOnMs == onMs && lastFallMs == fallMs &&
            lastOffMs == offMs && lastRepeat == repeat) {
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
    }

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
        powerOnIfNeeded(1) // always-on base; hue steps do the sweep
        FileUtils.writeLine(GRADIENT_NODE, "0")
        FileUtils.writeLine(REPEAT_NODE, "0")
        lastGradientSpeed = gradientSpeed
        lastColorHex = "sweep"
        sweepHandler.post(sweepTick)
    }
}
