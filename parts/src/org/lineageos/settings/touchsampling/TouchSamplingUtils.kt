/*
 * Copyright (C) 2015 The CyanogenMod Project
 *               2017-2019 The LineageOS Project
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
import android.util.Log
import org.lineageos.settings.utils.FileUtils

object TouchSamplingUtils {

    private const val TAG = "TouchSamplingUtils"
    const val HTSR_FILE = "/sys/devices/virtual/touch/touch_dev/bump_sample_rate"

    /**
     * Restore the touch sampling value from SharedPreferences and write it to the sysfs file.
     * This is typically called during boot or when the service restarts.
     *
     * @param context The application context
     */
    fun restoreSamplingValue(context: Context) {
        Log.i(TAG, "Restoring touch sampling value from SharedPreferences")
        
        val sharedPref = context.getSharedPreferences(
            TouchSamplingSettingsFragment.SHAREDHTSR,
            Context.MODE_PRIVATE
        )
        val htsrState = sharedPref.getInt(TouchSamplingSettingsFragment.SHAREDHTSR, 0)
        
        Log.d(TAG, "Restoring HTSR state: $htsrState")
        FileUtils.writeLine(HTSR_FILE, htsrState.toString())
    }
}
