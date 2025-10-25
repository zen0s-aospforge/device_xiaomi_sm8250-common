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

package org.lineageos.settings.thermal;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.util.Log;
import android.telecom.DefaultDialerManager;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;
import androidx.preference.PreferenceManager;

import com.android.settingslib.applications.AppUtils;

import org.lineageos.settings.utils.FileUtils;

import java.util.List;
import java.util.Map;

public final class ThermalUtils {

    private static final String TAG = "ThermalUtils";
    private static final String THERMAL_CONTROL = "thermal_control_v2";
    private static final String THERMAL_ENABLED = "thermal_enabled";

    protected static final int STATE_DEFAULT = 0;
    protected static final int STATE_BENCHMARK = 1;
    protected static final int STATE_GAMING = 2;
    protected static final int STATE_ULTRACOOL = 3;

    private static final Map<Integer, String> THERMAL_STATE_MAP = Map.of(
        STATE_DEFAULT, "0",
        STATE_BENCHMARK, "10",
        STATE_GAMING, "20",
        STATE_ULTRACOOL, "52"
    );

    private static final String THERMAL_BENCHMARK = "thermal.benchmark=";
    private static final String THERMAL_GAMING = "thermal.gaming=";
    private static final String THERMAL_DEFAULT = "thermal.default=";

    private static final String THERMAL_SCONFIG = "/sys/class/thermal/thermal_message/sconfig";

    private Context mContext;
    private Display mDisplay;
    private SharedPreferences mSharedPrefs;
    private Boolean mEnabled;
    private String mCurrentState;
    private Intent mServiceIntent;

    private static ThermalUtils sInstance;

    private ThermalUtils(Context context) {
        mContext = context;
        mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(context);

        WindowManager mWindowManager = context.getSystemService(WindowManager.class);
        mDisplay = mWindowManager.getDefaultDisplay();
        mEnabled = isEnabled();
        mServiceIntent = new Intent(context, ThermalService.class);
    }

    public static synchronized ThermalUtils getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new ThermalUtils(context);
        }
        return sInstance;
    }

    public void startService() {
        if (mEnabled) {
            dlog("startService");
            mContext.startServiceAsUser(mServiceIntent, UserHandle.CURRENT);
        }
    }

    private void stopService() {
        dlog("stopService");
        mContext.stopService(mServiceIntent);
    }

    protected Boolean isEnabled() {
        return mSharedPrefs.getBoolean(THERMAL_ENABLED, true);
    }

    protected void setEnabled(boolean enabled) {
        if (mEnabled == enabled) return;
        dlog("setEnabled: " + enabled);
        mEnabled = enabled;
        mSharedPrefs.edit().putBoolean(THERMAL_ENABLED, enabled).apply();
        if (enabled) {
            startService();
        } else {
            setDefaultThermalProfile();
            stopService();
        }
    }

    private void writeValue(String profiles) {
        mSharedPrefs.edit().putString(THERMAL_CONTROL, profiles).apply();
    }

    private String getValue() {
        String value = mSharedPrefs.getString(THERMAL_CONTROL, null);

        if (value == null || value.isEmpty()) {
            value = THERMAL_BENCHMARK + ":" + THERMAL_GAMING + ":" + THERMAL_DEFAULT;
            writeValue(value);
        }
        return value;
    }

    protected void writePackage(String packageName, int mode) {
        String value = getValue();
        value = value.replace(packageName + ",", "");
        String[] modes = value.split(":");
        String finalString;

        switch (mode) {
            case STATE_BENCHMARK:
                modes[0] = modes[0] + packageName + ",";
                break;
            case STATE_GAMING:
                modes[1] = modes[1] + packageName + ",";
                break;
            case STATE_DEFAULT:
                modes[2] = modes[2] + packageName + ",";
                break;
        }

        finalString = modes[0] + ":" + modes[1] + ":" + modes[2];

        writeValue(finalString);
    }

    protected int getStateForPackage(String packageName) {
        String value = getValue();
        String[] modes = value.split(":");
        int state = STATE_DEFAULT;

        if (modes[0].contains(packageName + ",")) {
            state = STATE_BENCHMARK;
        } else if (modes[1].contains(packageName + ",")) {
            state = STATE_GAMING;
        } else if (modes[2].contains(packageName + ",")) {
            state = STATE_DEFAULT;
        } else {
            // All unassigned apps default to STATE_DEFAULT
            state = STATE_DEFAULT;
        }

        return state;
    }

    protected void setDefaultThermalProfile() {
        FileUtils.writeLine(THERMAL_SCONFIG, THERMAL_STATE_MAP.get(STATE_DEFAULT));
    }

    protected void setThermalProfile(String packageName) {
        final int state = getStateForPackage(packageName);
        FileUtils.writeLine(THERMAL_SCONFIG, THERMAL_STATE_MAP.get(state));
    }

    private static void dlog(String msg) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, msg);
        }
    }
}
