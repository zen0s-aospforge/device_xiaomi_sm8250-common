/*
 * Copyright (C) 2015-2016 The CyanogenMod Project
 *               2017 The LineageOS Project
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

package org.lineageos.settings.display

import android.os.Bundle
import android.util.Log
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity

/**
 * Activity for display settings.
 * Hosts the DisplaySettingsFragment in a collapsing toolbar layout.
 */
class DisplaySettingsActivity : CollapsingToolbarBaseActivity() {

    companion object {
        private const val TAG = "DisplaySettingsActivity"
        private const val TAG_DCDIMMING = "dcdimming"
        private const val DEBUG = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            // Replace the content frame with our display settings fragment
            supportFragmentManager.beginTransaction()
                .replace(
                    com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                    DisplaySettingsFragment(),
                    TAG_DCDIMMING
                )
                .commit()

            if (DEBUG) Log.d(TAG, "DisplaySettingsFragment added to activity")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize DisplaySettingsFragment", e)
            // Could show error UI or finish activity if critical
        }
    }
}