package com.xiaomi.settings.light

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.widget.ImageView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.xiaomi.settings.R

/**
 * Non-clickable hero header for Back Light Effects (stock parity).
 *
 * Sets the drawable programmatically instead of relying on layout
 * inflation alone, and hides the row if the asset ever fails to
 * resolve — an empty card must never render.
 */
class HeroPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : Preference(context, attrs, defStyleAttr) {

    init {
        isSelectable = false
        layoutResource = R.layout.light_hero
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        // Never toggle isVisible here: adapter changes during layout
        // crash RecyclerView. Collapse the row view itself instead.
        val image = holder.findViewById(R.id.hero_image) as? ImageView
        if (image == null) {
            Log.w(TAG, "hero_image view missing, collapsing row")
            holder.itemView.visibility = View.GONE
            return
        }
        image.setImageResource(R.drawable.image_back_strap)
        if (image.drawable == null) {
            Log.w(TAG, "image_back_strap failed to resolve, collapsing row")
            holder.itemView.visibility = View.GONE
        } else {
            holder.itemView.visibility = View.VISIBLE
        }
    }

    companion object {
        private const val TAG = "HeroPreference"
    }
}
