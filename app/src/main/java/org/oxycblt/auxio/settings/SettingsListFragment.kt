/*
 * Copyright (c) 2021 Auxio Project
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
 
package org.oxycblt.auxio.settings

import android.os.Bundle
import android.view.View
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.updatePadding
import androidx.fragment.app.activityViewModels
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.children
import androidx.recyclerview.widget.RecyclerView
import coil.Coil
import org.oxycblt.auxio.R
import org.oxycblt.auxio.home.tabs.TabCustomizeDialog
import org.oxycblt.auxio.music.excluded.ExcludedDialog
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.playback.replaygain.ReplayGainMode
import org.oxycblt.auxio.playback.replaygain.PreAmpCustomizeDialog
import org.oxycblt.auxio.settings.pref.IntListPreference
import org.oxycblt.auxio.settings.pref.IntListPreferenceDialog
import org.oxycblt.auxio.ui.accent.AccentCustomizeDialog
import org.oxycblt.auxio.util.hardRestart
import org.oxycblt.auxio.util.isNight
import org.oxycblt.auxio.util.logD
import org.oxycblt.auxio.util.showToast
import org.oxycblt.auxio.util.systemBarInsetsCompat

/**
 * The actual fragment containing the settings menu. Inherits [PreferenceFragmentCompat].
 * @author OxygenCobalt
 *
 * TODO: Add option to restore the previous state
 *
 * TODO: Add option to not restore state
 */
@Suppress("UNUSED")
class SettingsListFragment : PreferenceFragmentCompat() {
    private val playbackModel: PlaybackViewModel by activityViewModels()
    val settingsManager = SettingsManager.getInstance()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        preferenceManager.onDisplayPreferenceDialogListener = this
        preferenceScreen.children.forEach(::recursivelyHandlePreference)

        // Make the RecycleView edge-to-edge capable
        view.findViewById<RecyclerView>(androidx.preference.R.id.recycler_view).apply {
            clipToPadding = false

            setOnApplyWindowInsetsListener { _, insets ->
                updatePadding(bottom = insets.systemBarInsetsCompat.bottom)
                insets
            }
        }

        logD("Fragment created")
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.prefs_main, rootKey)
    }

    @Suppress("Deprecation")
    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference is IntListPreference) {
            // Creating our own preference dialog is hilariously difficult. For one, we need
            // to override this random method within the class in order to launch the dialog in
            // the first (because apparently you can't just implement some interface that
            // automatically provides this behavior), then we also need to use a deprecated method
            // to adequately supply a "target fragment" (otherwise we will crash since the dialog
            // requires one), and then we need to actually show the dialog, making sure we use
            // the parent FragmentManager as again, it will crash if we don't.
            //
            // Fragments were a mistake.
            val dialog = IntListPreferenceDialog.from(preference)
            dialog.setTargetFragment(this, 0)
            dialog.show(parentFragmentManager, IntListPreferenceDialog.TAG)
        } else {
            super.onDisplayPreferenceDialog(preference)
        }
    }

    /** Recursively handle a preference, doing any specific actions on it. */
    private fun recursivelyHandlePreference(preference: Preference) {
        if (!preference.isVisible) return

        if (preference is PreferenceCategory) {
            for (child in preference.children) {
                recursivelyHandlePreference(child)
            }
        }

        preference.apply {
            when (key) {
                SettingsManager.KEY_THEME -> {
                    setIcon(AppCompatDelegate.getDefaultNightMode().toThemeIcon())

                    onPreferenceChangeListener =
                        Preference.OnPreferenceChangeListener { _, value ->
                            AppCompatDelegate.setDefaultNightMode(value as Int)
                            setIcon(AppCompatDelegate.getDefaultNightMode().toThemeIcon())
                            true
                        }
                }
                SettingsManager.KEY_BLACK_THEME -> {
                    onPreferenceClickListener =
                        Preference.OnPreferenceClickListener {
                            if (requireContext().isNight) {
                                requireActivity().recreate()
                            }

                            true
                        }
                }
                SettingsManager.KEY_ACCENT -> {
                    onPreferenceClickListener =
                        Preference.OnPreferenceClickListener {
                            AccentCustomizeDialog()
                                .show(childFragmentManager, AccentCustomizeDialog.TAG)
                            true
                        }

                    summary = context.getString(settingsManager.accent.name)
                }
                SettingsManager.KEY_LIB_TABS -> {
                    onPreferenceClickListener =
                        Preference.OnPreferenceClickListener {
                            TabCustomizeDialog().show(childFragmentManager, TabCustomizeDialog.TAG)
                            true
                        }
                }
                SettingsManager.KEY_SHOW_COVERS, SettingsManager.KEY_QUALITY_COVERS -> {
                    onPreferenceChangeListener =
                        Preference.OnPreferenceChangeListener { _, _ ->
                            Coil.imageLoader(requireContext()).apply { this.memoryCache?.clear() }
                            true
                        }
                }
                SettingsManager.KEY_REPLAY_GAIN -> {
                    notifyDependencyChange(settingsManager.replayGainMode == ReplayGainMode.OFF)
                    onPreferenceChangeListener =
                        Preference.OnPreferenceChangeListener { _, value ->
                            notifyDependencyChange(
                                ReplayGainMode.fromIntCode(value as Int) == ReplayGainMode.OFF)
                            true
                        }
                }
                SettingsManager.KEY_PRE_AMP -> {
                    onPreferenceClickListener =
                        Preference.OnPreferenceClickListener {
                            PreAmpCustomizeDialog()
                                .show(childFragmentManager, PreAmpCustomizeDialog.TAG)
                            true
                        }
                }
                SettingsManager.KEY_SAVE_STATE -> {
                    onPreferenceClickListener =
                        Preference.OnPreferenceClickListener {
                            playbackModel.savePlaybackState(requireContext()) {
                                requireContext().showToast(R.string.lbl_state_saved)
                            }

                            true
                        }
                }
                SettingsManager.KEY_RELOAD -> {
                    onPreferenceClickListener =
                        Preference.OnPreferenceClickListener {
                            playbackModel.savePlaybackState(requireContext()) {
                                requireContext().hardRestart()
                            }

                            true
                        }
                }
                SettingsManager.KEY_EXCLUDED -> {
                    onPreferenceClickListener =
                        Preference.OnPreferenceClickListener {
                            ExcludedDialog().show(childFragmentManager, ExcludedDialog.TAG)
                            true
                        }
                }
            }
        }
    }

    /** Convert an theme integer into an icon that can be used. */
    @DrawableRes
    private fun Int.toThemeIcon(): Int {
        return when (this) {
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM -> R.drawable.ic_auto
            AppCompatDelegate.MODE_NIGHT_NO -> R.drawable.ic_day
            AppCompatDelegate.MODE_NIGHT_YES -> R.drawable.ic_night
            else -> R.drawable.ic_auto
        }
    }
}
