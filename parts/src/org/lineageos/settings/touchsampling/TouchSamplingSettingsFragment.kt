/*
 * Copyright (C) 2018 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lineageos.settings.touchsampling

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.MenuItem
import androidx.preference.Preference
import androidx.preference.Preference.OnPreferenceChangeListener
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import android.util.Log
import org.lineageos.settings.R
import org.lineageos.settings.utils.FileUtils

class TouchSamplingSettingsFragment : PreferenceFragmentCompat(),
    OnPreferenceChangeListener {

    companion object {
        private const val HTSR_ENABLE_KEY = "htsr_enable"
        const val SHAREDHTSR = "SHAREDHTSR"
        private const val TAG = "TouchSamplingFragment"
    }

    private lateinit var mHTSRPreference: SwitchPreferenceCompat

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.htsr_settings)
        requireActivity().actionBar?.setDisplayHomeAsUpEnabled(true)

        mHTSRPreference = findPreference(HTSR_ENABLE_KEY) ?: run {
            Log.e(TAG, "HTSR preference not found")
            return
        }
        mHTSRPreference.isEnabled = true
        mHTSRPreference.onPreferenceChangeListener = this
        enableHTSR(0)
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
        return when (preference.key) {
            HTSR_ENABLE_KEY -> {
                enableHTSR(if (newValue as Boolean) 1 else 0)
                true
            }
            else -> false
        }
    }

    @Suppress("DEPRECATION")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                requireActivity().onBackPressedDispatcher.onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun enableHTSR(enable: Int) {
        Log.i(TAG, "Enabling HTSR: enable=$enable")
        
        // Write to sysfs file
        FileUtils.writeLine(TouchSamplingUtils.HTSR_FILE, enable.toString())
        
        // Save to SharedPreferences
        val preferences = requireActivity().getSharedPreferences(SHAREDHTSR, Context.MODE_PRIVATE)
        preferences.edit().apply {
            putInt(SHAREDHTSR, enable)
            apply()
            Log.d(TAG, "Saved HTSR state to SharedPreferences: $enable")
        }
    }
}
