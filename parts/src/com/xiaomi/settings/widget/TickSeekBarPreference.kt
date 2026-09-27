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
import android.widget.TextView
import androidx.preference.PreferenceViewHolder
import androidx.preference.SeekBarPreference

/**
 * SeekBarPreference with locking-tick feel: a dotted tick track, a
 * percentage readout in the summary slot, a haptic knock each time the
 * thumb crosses a detent while dragging, and a magnetic snap on release.
 *
 * Why the touch plumbing (read before "simplifying" it): androidx defers
 * the value/OnPreferenceChangeListener sync to onStopTrackingTouch while
 * dragging (mUpdatesContinuously is false by default), and that callback
 * runs *after* this touch listener on ACTION_UP. Stamping
 * `summary = percent(value)` here therefore read the pre-drag value —
 * the label showed where the drag started, not where it ended. We now:
 *  (a) enable continuous sync so the effect applies live under the finger,
 *  (b) paint the readout straight into the summary TextView during the
 *      drag (going through `summary` would notifyChanged every move),
 *  (c) do the final snap + change-listener sync ourselves, so
 *      applied == stored == readout, then stamp from the real value.
 * Out-of-band changes (keyboard/DPAD) self-heal via a post from bind —
 * stamping there directly notifies RecyclerView mid-layout (crash).
 * Haptics and snapping ride on a touch listener, so the preference's
 * own value plumbing stays untouched. Shared by display saturation and
 * the LED sliders.
 */
class TickSeekBarPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.seekBarPreferenceStyle,
    defStyleRes: Int = 0,
) : SeekBarPreference(context, attrs, defStyleAttr, defStyleRes) {

    private var lastDetent = -1
    private var summaryView: TextView? = null

    init {
        // Live apply while dragging — without this the screen only
        // updates on finger-lift and the slider feels disconnected
        // from the effect it controls.
        updatesContinuously = true
    }

    override fun onAttached() {
        super.onAttached()
        // A stored value can exceed a lowered max (saturation shipped
        // 0-150 before the framework's 100 clamp was found); setValue
        // clamps into min..max and persists, so thumb and label agree.
        if (value !in min..max) setValue(value)
        refreshSummary()
    }

    /** Re-stamp the readout after external changes (e.g. reset button). */
    fun refreshSummary() {
        summary = percent(value)
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        summaryView = holder.findViewById(android.R.id.summary) as? TextView

        val bar = holder.findViewById(androidx.preference.R.id.seekbar) as? SeekBar
        if (bar == null) return
        if (bar.tickMark == null) {
            bar.tickMark = context.getDrawable(com.xiaomi.settings.R.drawable.seek_tick)
        }

        // Reconcile out-of-band value changes after the current layout
        // pass. Safe: a matching summary does not notify, so this never
        // loops; a mismatch notifies exactly once, after layout.
        holder.itemView.post {
            if (value !in min..max) setValue(value)
            else if (summary != percent(value)) refreshSummary()
        }

        bar.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val detent = bar.progress / DETENT_EVERY
                    if (detent != lastDetent) {
                        lastDetent = detent
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    // Live readout: write the TextView directly; routing
                    // through `summary` would rebind on every move.
                    summaryView?.text = percent(min + bar.progress)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Magnetic levels: snap to the nearest detent so the
                    // thumb locks in.
                    val snapped = (Math.round(bar.progress / DETENT_EVERY.toFloat()) * DETENT_EVERY)
                        .coerceIn(bar.min, bar.max)
                    if (snapped != bar.progress) bar.progress = snapped
                    // Sync applied/stored/readout ourselves: the framework's
                    // own stop-tracking sync runs after this listener (and
                    // only if progress != value), so waiting for it left the
                    // label one step behind. Rejecting the listener reverts
                    // the thumb to the stored value, stock behavior.
                    val snappedValue = min + snapped
                    if (value != snappedValue) {
                        if (callChangeListener(snappedValue)) {
                            setValue(snappedValue)
                        } else {
                            bar.progress = value - min
                        }
                    }
                    lastDetent = -1
                    refreshSummary()
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
