// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Sideview VR contributors
package app.sideviewvr

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView

/**
 * Material 3 style (dark scheme) built from platform widgets, so no extra library is needed:
 * tonal pill buttons, filter-chip tabs, a rounded surface panel and primary-tinted sliders.
 */
object Ui {
    const val SURFACE = 0xF01C1B1F.toInt()
    const val BACKGROUND = 0xFF141218.toInt()
    const val SURFACE_HIGH = 0xFF2B2930.toInt()
    const val ON_SURFACE = 0xFFE6E1E5.toInt()
    const val ON_SURFACE_VARIANT = 0xFFCAC4D0.toInt()
    const val PRIMARY = 0xFFD0BCFF.toInt()
    const val ON_PRIMARY = 0xFF381E72.toInt()
    const val SECONDARY_CONTAINER = 0xFF4A4458.toInt()
    const val ON_SECONDARY_CONTAINER = 0xFFE8DEF8.toInt()
    const val OUTLINE = 0xFF938F99.toInt()

    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private fun dp(c: Context, v: Float) = v * c.resources.displayMetrics.density

    fun shape(c: Context, color: Int, radiusDp: Float, strokeColor: Int = 0): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(c, radiusDp)
        if (strokeColor != 0) setStroke(dp(c, 1f).toInt(), strokeColor)
    }

    private fun ripple(c: Context, content: Drawable, radiusDp: Float): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, shape(c, 0xFFFFFFFF.toInt(), radiusDp))

    /** Tonal button (default), filled primary button, or text-only. */
    enum class Kind { TONAL, FILLED, TEXT }

    fun styleButton(b: Button, kind: Kind = Kind.TONAL, radiusDp: Float = 20f) {
        val c = b.context
        b.isAllCaps = false
        b.typeface = medium
        b.stateListAnimator = null
        b.elevation = 0f
        val (bg, fg) = when (kind) {
            Kind.TONAL -> SURFACE_HIGH to ON_SURFACE
            Kind.FILLED -> PRIMARY to ON_PRIMARY
            Kind.TEXT -> 0 to PRIMARY
        }
        b.background = ripple(c, shape(c, bg, radiusDp), radiusDp)
        b.setTextColor(fg)
    }

    /** Filter chip: selected = secondary container, otherwise outlined. */
    fun styleChip(b: Button, selected: Boolean) {
        val c = b.context
        b.isAllCaps = false; b.typeface = medium; b.stateListAnimator = null; b.elevation = 0f
        b.background = ripple(c, if (selected) shape(c, SECONDARY_CONTAINER, 8f) else shape(c, 0, 8f, OUTLINE), 8f)
        b.setTextColor(if (selected) ON_SECONDARY_CONTAINER else ON_SURFACE_VARIANT)
    }

    fun styleSlider(s: SeekBar) {
        s.progressTintList = ColorStateList.valueOf(PRIMARY)
        s.thumbTintList = ColorStateList.valueOf(PRIMARY)
        s.progressBackgroundTintList = ColorStateList.valueOf(SECONDARY_CONTAINER)
    }

    fun styleCheck(cb: CheckBox) { cb.buttonTintList = ColorStateList.valueOf(PRIMARY); cb.setTextColor(ON_SURFACE_VARIANT) }

    fun title(t: TextView, sp: Float = 16f) { t.typeface = medium; t.textSize = sp; t.setTextColor(ON_SURFACE) }
    fun body(t: TextView) { t.setTextColor(ON_SURFACE); t.textSize = 14f }
    fun supporting(t: TextView) { t.setTextColor(ON_SURFACE_VARIANT); t.textSize = 12f }
}
