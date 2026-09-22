package com.xiaomi.settings.light

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.xiaomi.settings.R

/**
 * Shared circle-row color picker for every Back Light Effects section
 * (standalone / charging / incoming / camera / notifications / music /
 * game). One class, one palette, zero duplication.
 *
 * Persists a lowercase hex string ("ff0000", …, "ffffff") or "gradient"
 * (rainbow swatch) under its own key — the same value shape the old
 * ListPreferences stored, so existing user settings carry over.
 */
class LedColorPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : Preference(context, attrs, defStyleAttr) {

    init {
        isSelectable = false
        layoutResource = R.layout.led_color_picker
    }

    private var defaultHex = VALUE_GRADIENT

    override fun onGetDefaultValue(a: android.content.res.TypedArray, index: Int): Any? =
        a.getString(index)

    override fun onSetInitialValue(defaultValue: Any?) {
        defaultHex = defaultValue as? String ?: VALUE_GRADIENT
        persistString(getPersistedString(defaultHex))
    }

    private fun currentValue(): String = getPersistedString(defaultHex)

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val row = holder.findViewById(R.id.swatches) as? LinearLayout ?: return
        row.removeAllViews()
        val current = currentValue()
        for (hex in PALETTE) {
            row.addView(swatch(hex, hex == current))
        }
    }

    private fun swatch(hex: String, selected: Boolean): View =
        View(context).apply {
            val size = dp(SWATCH_DP)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = dp(SWATCH_GAP_DP)
            }
            background = circle(hex, selected)
            contentDescription = hex
            setOnClickListener { setColor(hex) }
        }

    private fun circle(hex: String, selected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (hex == VALUE_GRADIENT) {
                gradientType = GradientDrawable.SWEEP_GRADIENT
                colors = RAINBOW
            } else if (hex.equals("ffffff", ignoreCase = true)) {
                setColor(Color.WHITE)
            } else {
                setColor(COLORS[hex] ?: Color.GRAY)
            }
            val stroke = dp(if (selected) 3 else 1)
            setStroke(stroke, if (selected) Color.WHITE else Color.GRAY)
        }

    private fun setColor(hex: String) {
        if (!callChangeListener(hex)) return
        persistString(hex)
        notifyChanged()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics,
        ).toInt()

    companion object {
        const val VALUE_GRADIENT = "gradient"

        /** 9 across with room to spare: no hidden overflow, no scroll hunt. */
        private const val SWATCH_DP = 30
        private const val SWATCH_GAP_DP = 8

        /** Single palette for every section: colors, white, rainbow last. */
        val PALETTE = listOf(
            "ff0000",
            "ff7f00",
            "ffff00",
            "00ff00",
            "00ffff",
            "0000ff",
            "800080",
            "ffffff",
            VALUE_GRADIENT,
        )

        /** Parsed once: onBind rebuilds 9 swatches per rebind otherwise. */
        private val COLORS = mapOf(
            "ff0000" to Color.RED,
            "ff7f00" to Color.parseColor("#ff7f00"),
            "ffff00" to Color.YELLOW,
            "00ff00" to Color.GREEN,
            "00ffff" to Color.CYAN,
            "0000ff" to Color.BLUE,
            "800080" to Color.parseColor("#800080"),
            "ffffff" to Color.WHITE,
        )
        private val RAINBOW = intArrayOf(
            Color.RED, Color.parseColor("#ff7f00"), Color.YELLOW,
            Color.GREEN, Color.CYAN, Color.BLUE, Color.parseColor("#800080"),
        )
    }
}
