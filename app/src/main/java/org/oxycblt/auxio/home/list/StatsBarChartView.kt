/*
 * Copyright (c) 2026 Auxio Project
 * StatsBarChartView.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.oxycblt.auxio.home.list

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.google.android.material.R as MR
import org.oxycblt.auxio.util.getAttrColorCompat

/** A small bar chart: one bar per value, tallest bar solid, the rest faded, labels underneath. */
class StatsBarChartView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val barPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getAttrColorCompat(androidx.appcompat.R.attr.colorPrimary).defaultColor
        }
    private val labelPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getAttrColorCompat(MR.attr.colorOnSecondaryContainer).defaultColor
            textAlign = Paint.Align.CENTER
            textSize = 11f * resources.displayMetrics.scaledDensity
        }
    private val rect = RectF()
    private var values = FloatArray(0)
    private var labels = emptyList<String>()

    /** Show [values] (any scale) with an optional [labels] entry for each. */
    fun setData(values: FloatArray, labels: List<String>) {
        this.values = values
        this.labels = labels
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val count = values.size
        if (count == 0) return
        val hasLabels = labels.any { it.isNotEmpty() }
        val labelHeight = if (hasLabels) 18f * density else 0f
        val chartHeight = height - labelHeight
        val gap = (if (count > 14) 2f else 6f) * density
        val barWidth = (width - gap * (count - 1)) / count
        val max = values.max().takeIf { it > 0f } ?: 1f
        values.forEachIndexed { i, value ->
            val left = i * (barWidth + gap)
            // Empty periods still get a stub so the row reads as a chart.
            val barHeight = (chartHeight * value / max).coerceAtLeast(3f * density)
            barPaint.alpha = if (value == max) 255 else 140
            rect.set(left, chartHeight - barHeight, left + barWidth, chartHeight)
            val radius = minOf(barWidth / 2, 6f * density)
            canvas.drawRoundRect(rect, radius, radius, barPaint)
            labels.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let {
                canvas.drawText(it, left + barWidth / 2, height - 3f * density, labelPaint)
            }
        }
    }
}
