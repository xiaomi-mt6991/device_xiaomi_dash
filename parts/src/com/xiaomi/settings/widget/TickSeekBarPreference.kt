/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.widget

import android.content.Context
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.widget.SeekBar
import androidx.preference.PreferenceViewHolder
import androidx.preference.SeekBarPreference

/**
 * SeekBarPreference with locking-tick feel: a dotted tick track, a
 * percentage readout in the summary slot, plus a haptic knock each
 * time the thumb crosses a detent while dragging.
 *
 * The readout lives in the summary (not the stock value view) so there
 * is exactly one writer and no flicker fight. Haptics and snapping ride
 * on a touch listener so the preference's own value plumbing is
 * untouched. Shared by the display saturation and LED sliders.
 */
class TickSeekBarPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.seekBarPreferenceStyle,
    defStyleRes: Int = 0,
) : SeekBarPreference(context, attrs, defStyleAttr, defStyleRes) {

    private var lastDetent = -1

    override fun onAttached() {
        super.onAttached()
        // Initial readout. Done here — never in onBindViewHolder, where
        // an adapter notify crashes RecyclerView mid-layout.
        refreshSummary()
    }

    /** Re-stamp the readout after external changes (e.g. reset button). */
    fun refreshSummary() {
        summary = percent(value)
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val bar = holder.findViewById(androidx.preference.R.id.seekbar) as? SeekBar
        if (bar == null) return
        if (bar.tickMark == null) {
            bar.tickMark = context.getDrawable(com.xiaomi.settings.R.drawable.seek_tick)
        }
        bar.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val detent = bar.progress / DETENT_EVERY
                    if (detent != lastDetent) {
                        lastDetent = detent
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    // Magnetic levels: snap to the nearest detent so the
                    // thumb locks in. The programmatic set persists through
                    // the preference's own listener. Touch dispatch is
                    // outside layout, so stamping the summary is safe.
                    val snapped = (Math.round(bar.progress / DETENT_EVERY.toFloat()) * DETENT_EVERY)
                        .coerceIn(bar.min, bar.max)
                    if (snapped != bar.progress) bar.progress = snapped
                    summary = percent(value)
                }
            }
            false
        }
    }

    private fun percent(progress: Int): String = "$progress%"

    companion object {
        /** One haptic knock per this many slider units (20 detents). */
        private const val DETENT_EVERY = 5
    }
}
