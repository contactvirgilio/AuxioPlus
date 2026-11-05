/*
 * Copyright (c) 2026 Auxio Project
 * MarqueeTextView.kt is part of Auxio.
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

package org.oxycblt.auxio.ui

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * A [AppCompatTextView] that scrolls automatically when its text doesn't fit, without needing to be
 * focused or selected. Selection is avoided on purpose since list items use it for the "playing"
 * highlight.
 */
class MarqueeTextView
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) :
    AppCompatTextView(context, attrs, defStyleAttr) {
    // TextView only starts its marquee when it believes it is focused.
    override fun isFocused() = true

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Nudge the marquee to (re)start now that the text and width are known.
        onWindowFocusChanged(true)
    }
}
