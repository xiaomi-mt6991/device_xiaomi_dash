/*
 * SPDX-FileCopyrightText: The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.lights

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.xiaomi.settings.utils.FileUtils

/**
 * Drives the AW21024 rear RGB LED.
 *
 * Only two sysfs nodes are used, both with stable formats: [NODE_RGB] takes
 * "R G B" decimal ("255 0 0" for red — verify on device with
 * `echo "255 0 0" > .../rgbcolor`), [NODE_BRIGHTNESS] takes a plain integer.
 * All effects (blink/breath/pulse) are rendered in software on a dedicated
 * thread so no driver effect-node formats are needed.
 */
object LedController {

    private const val TAG = "BacklightLed"

    const val BASE = "/sys/class/leds/aw21024_led"
    private const val NODE_RGB = "$BASE/rgbcolor"
    private const val NODE_BRIGHTNESS = "$BASE/brightness"
    private const val NODE_MAX = "$BASE/max_brightness"

    @Volatile private var cachedMax = -1

    fun isAvailable(): Boolean =
        FileUtils.isFileWritable(NODE_RGB) && FileUtils.isFileWritable(NODE_BRIGHTNESS)

    fun maxLevel(): Int {
        if (cachedMax > 0) return cachedMax
        cachedMax = FileUtils.readOneLine(NODE_MAX)?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: 255
        return cachedMax
    }

    fun setColor(color: Int) {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        FileUtils.writeLine(NODE_RGB, "$r $g $b")
    }

    fun setLevel(level: Int) {
        FileUtils.writeLine(NODE_BRIGHTNESS, level.coerceIn(0, maxLevel()).toString())
    }

    fun clear() = setLevel(0)

    /**
     * Software effect engine. [play] replaces any running effect; [stop]
     * turns the LED off. [onDone] fires for finite effects (repeat >= 0).
     */
    class Engine {

        sealed interface Effect {
            data object Off : Effect
            data class Steady(val color: Int) : Effect
            data class Blink(
                val color: Int,
                val onMs: Long,
                val offMs: Long,
                val repeat: Int = -1,
            ) : Effect
            data class Breath(val color: Int, val periodMs: Long) : Effect
        }

        private val thread = HandlerThread(TAG).apply { start() }
        private val handler = Handler(thread.looper)

        @Volatile private var generation = 0
        @Volatile private var brightness = 1f

        @Synchronized
        fun play(effect: Effect, brightness: Float, onDone: (() -> Unit)? = null) {
            generation++
            this.brightness = brightness.coerceIn(0f, 1f)
            val gen = generation
            when (effect) {
                is Effect.Off -> {
                    handler.post { LedController.clear() }
                }
                is Effect.Steady -> {
                    handler.post { if (gen == generation) steady(effect.color) }
                }
                is Effect.Blink -> {
                    handler.post { if (gen == generation) blink(gen, effect, onDone) }
                }
                is Effect.Breath -> {
                    handler.post { if (gen == generation) breath(gen, effect) }
                }
            }
        }

        @Synchronized
        fun stop() {
            generation++
            handler.post { LedController.clear() }
        }

        fun release() {
            generation++
            thread.quitSafely()
        }

        private fun level(): Int = (brightness * LedController.maxLevel()).toInt()

        private fun steady(color: Int) {
            LedController.setColor(color)
            LedController.setLevel(level())
        }

        private fun blink(gen: Int, effect: Effect.Blink, onDone: (() -> Unit)?) {
            LedController.setColor(effect.color)
            var left = effect.repeat
            while (gen == generation) {
                LedController.setLevel(level())
                if (!sleep(effect.onMs, gen)) return
                LedController.setLevel(0)
                if (left > 0) {
                    left--
                    if (left == 0) break
                }
                if (!sleep(effect.offMs, gen)) return
            }
            if (gen == generation) onDone?.invoke()
        }

        private fun breath(gen: Int, effect: Effect.Breath) {
            LedController.setColor(effect.color)
            val steps = 24
            val stepMs = (effect.periodMs / (steps * 2)).coerceAtLeast(20L)
            val peak = level()
            while (gen == generation) {
                for (i in 0..steps) {
                    if (gen != generation) return
                    LedController.setLevel(peak * i / steps)
                    Thread.sleep(stepMs)
                }
                for (i in steps downTo 0) {
                    if (gen != generation) return
                    LedController.setLevel(peak * i / steps)
                    Thread.sleep(stepMs)
                }
            }
        }

        private fun sleep(ms: Long, gen: Int): Boolean {
            if (ms <= 0) return gen == generation
            Thread.sleep(ms)
            return gen == generation
        }
    }
}
