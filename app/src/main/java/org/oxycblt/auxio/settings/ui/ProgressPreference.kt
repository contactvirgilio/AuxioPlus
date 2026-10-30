/*
 * Copyright (c) 2026 Auxio Project
 * ProgressPreference.kt is part of Auxio.
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
 
package org.oxycblt.auxio.settings.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.text.NumberFormat
import org.oxycblt.auxio.R

/**
 * A clickable [Preference] that can show a live progress bar with an "x of y" count and a
 * percentage underneath its title while a long-running action it started is going on, and an error
 * in place of its summary if that action failed.
 */
class ProgressPreference
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.preferenceStyle,
    defStyleRes: Int = 0,
) : Preference(context, attrs, defStyleAttr, defStyleRes) {
    private var state: State = State.Idle

    init {
        layoutResource = R.layout.view_preference_progress
    }

    /**
     * Show the progress bar in place of the summary.
     *
     * @param title The title to show while the action is running.
     * @param done The amount of items handled so far.
     * @param total The total amount of items, or 0 if not known yet (shows an indeterminate bar).
     */
    fun showProgress(title: CharSequence, done: Int, total: Int) {
        updateState(State.Running(title, done, total))
    }

    /**
     * Show an error in place of the summary until the next [showProgress] or [showIdle].
     *
     * @param error The error to show.
     */
    fun showError(error: CharSequence) {
        updateState(State.Failed(error))
    }

    /** Go back to showing the normal title and summary. */
    fun showIdle() {
        updateState(State.Idle)
    }

    private fun updateState(newState: State) {
        if (state == newState) return
        state = newState
        notifyChanged()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val titleView = holder.findViewById(android.R.id.title) as TextView
        val summaryView = holder.findViewById(android.R.id.summary) as TextView
        val container = requireNotNull(holder.findViewById(R.id.preference_progress_container))
        val progressBar = holder.findViewById(R.id.preference_progress) as LinearProgressIndicator
        val countView = holder.findViewById(R.id.preference_progress_count) as TextView
        val percentView = holder.findViewById(R.id.preference_progress_percent) as TextView

        when (val current = state) {
            is State.Idle -> {
                container.isVisible = false
                // Reset to indeterminate while hidden, as some versions of the progress indicator
                // refuse to switch to indeterminate mode while visible.
                progressBar.isIndeterminate = true
            }
            is State.Running -> {
                titleView.text = current.title
                summaryView.isVisible = false
                if (current.total == 0 && !container.isVisible) {
                    // Not known yet, so start indeterminate. Done before showing the bar for
                    // the same reason as above.
                    progressBar.isIndeterminate = true
                }
                container.isVisible = true
                if (current.total > 0) {
                    val fraction = current.done.toFloat() / current.total
                    // On an indeterminate bar this smoothly switches it to determinate mode.
                    progressBar.setProgressCompat((fraction * progressBar.max).toInt(), true)
                    countView.text =
                        context.getString(
                            R.string.fmt_playlist_progress,
                            current.done,
                            current.total,
                        )
                    percentView.text = NumberFormat.getPercentInstance().format(fraction)
                } else {
                    countView.text = null
                    percentView.text = null
                }
            }
            is State.Failed -> {
                container.isVisible = false
                progressBar.isIndeterminate = true
                summaryView.isVisible = true
                summaryView.text = current.error
            }
        }
    }

    private sealed interface State {
        data object Idle : State

        data class Running(val title: CharSequence, val done: Int, val total: Int) : State

        data class Failed(val error: CharSequence) : State
    }
}
