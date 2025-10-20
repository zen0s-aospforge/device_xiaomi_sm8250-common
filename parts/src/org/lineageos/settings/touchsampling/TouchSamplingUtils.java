/*
 * Copyright (C) 2024 The LineageOS Project
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

package org.lineageos.settings.touchsampling;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import androidx.preference.PreferenceManager;

import org.lineageos.settings.touchsampling.TouchSamplingSettingsFragment;
import org.lineageos.settings.utils.FileUtils;

import java.util.List;

import org.lineageos.settings.touchsampling.TouchFeatureController;

public final class TouchSamplingUtils {
    private static final String TAG = "TouchSamplingUtils";
    public static final String HTSR_FILE = "/sys/devices/platform/goodix_ts.0/switch_report_rate";
    public static final String SCONFIG_FILE = "/sys/class/thermal/thermal_message/sconfig";

    public static void restoreSamplingValue(Context context) {
        SharedPreferences sharedPref = context.getSharedPreferences(
                TouchSamplingSettingsFragment.SHAREDHTSR, Context.MODE_PRIVATE);
        boolean enabled = sharedPref.getBoolean(TouchSamplingSettingsFragment.HTSR_STATE, false);
        // Delegate to controller which will use AIDL if available and fall back to sysfs
        TouchFeatureController.INSTANCE.setHighTouchSamplingEnabled(enabled);
    }
}
