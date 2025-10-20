/*
 * Copyright (C) 2025 The LineageOS Project
 * Copyright (C) 2025 kenway214
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

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;

import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.PreferenceFragment;
import androidx.preference.SwitchPreferenceCompat;

import org.lineageos.settings.R;
import org.lineageos.settings.touchsampling.TouchSamplingUtils;
import org.lineageos.settings.utils.FileUtils;

public class TouchSamplingSettingsFragment extends PreferenceFragment implements Preference.OnPreferenceChangeListener {

    private static final String HTSR_ENABLE_KEY = "htsr_enable";
    public static final String SHAREDHTSR = "SHAREDHTSR";
    public static final String HTSR_STATE = "htsr_state";
    
    // Added constants for notification
    private static final int NOTIFICATION_ID = 3;
    private static final String NOTIFICATION_CHANNEL_ID = "touch_sampling_tile_service_channel";

    private SwitchPreferenceCompat mHTSRPreference;
    private SharedPreferences mPrefs;
    private VideoPreference videoPreference;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.htsr_settings);
        getActivity().getActionBar().setDisplayHomeAsUpEnabled(true);

        mPrefs = getActivity().getSharedPreferences(SHAREDHTSR, Context.MODE_PRIVATE);

        // Set the initial state of the main toggle
        mHTSRPreference = (SwitchPreferenceCompat) findPreference(HTSR_ENABLE_KEY);
        boolean htsrEnabled = mPrefs.getBoolean(HTSR_STATE, false);
        mHTSRPreference.setChecked(htsrEnabled);
        mHTSRPreference.setOnPreferenceChangeListener(this);

        // Setup the automatic screen control toggle
        SwitchPreferenceCompat autoScreenControlPref = (SwitchPreferenceCompat) findPreference("htsr_auto_screen_control");
        boolean autoScreenControl = mPrefs.getBoolean("htsr_auto_screen_control", true);
        autoScreenControlPref.setChecked(autoScreenControl);
        autoScreenControlPref.setOnPreferenceChangeListener(this);

        // Setup the new auto-enable for selected apps toggle
        SwitchPreferenceCompat autoEnableSelectedAppsPref = (SwitchPreferenceCompat) findPreference("htsr_auto_enable_selected_apps");
        boolean autoEnableSelectedApps = mPrefs.getBoolean("htsr_auto_enable_selected_apps", true);
        autoEnableSelectedAppsPref.setChecked(autoEnableSelectedApps);
        autoEnableSelectedAppsPref.setOnPreferenceChangeListener(this);

        // Find the VideoPreference (if any)
        videoPreference = (VideoPreference) findPreference("htsr_media");

        // Wire up app selector/remover
        Preference perAppConfigPref = findPreference("htsr_per_app_config");
        if (perAppConfigPref != null) {
            perAppConfigPref.setOnPreferenceClickListener(pref -> {
                startActivity(new android.content.Intent(getContext(), TouchSamplingPerAppConfigActivity.class));
                return true;
            });
        }

        // Start service if main toggle or auto-enable for selected apps is ON
        if (htsrEnabled || autoEnableSelectedApps) {
            startTouchSamplingService(true);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (videoPreference != null) {
            videoPreference.restartVideo();
        }
        
        // Synchronize UI state with service state
        synchronizeMainSwitchState();
    }
    
    /**
     * Ensures the main switch UI reflects the actual service state
     */
    private void synchronizeMainSwitchState() {
        try {
            boolean currentMainState = mPrefs.getBoolean(HTSR_STATE, false);
            boolean currentAutoScreenControl = mPrefs.getBoolean("htsr_auto_screen_control", true);
            boolean currentAutoApp = mPrefs.getBoolean("htsr_auto_enable_selected_apps", true);
            
            // Update UI to reflect current state
            if (mHTSRPreference != null) {
                mHTSRPreference.setChecked(currentMainState);
            }
            
            SwitchPreferenceCompat autoScreenControlPref = (SwitchPreferenceCompat) findPreference("htsr_auto_screen_control");
            if (autoScreenControlPref != null) {
                autoScreenControlPref.setChecked(currentAutoScreenControl);
            }
            
            SwitchPreferenceCompat autoAppPref = (SwitchPreferenceCompat) findPreference("htsr_auto_enable_selected_apps");
            if (autoAppPref != null) {
                autoAppPref.setChecked(currentAutoApp);
            }
            
            // Ensure service is running if any feature is enabled
            boolean shouldRunService = currentMainState || currentAutoScreenControl || currentAutoApp;
            if (shouldRunService) {
                ensureServiceRunning();
            }
            
        } catch (Exception e) {
            android.util.Log.e("TouchSamplingSettings", "Error synchronizing main switch state", e);
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (HTSR_ENABLE_KEY.equals(preference.getKey())) {
            boolean isEnabled = (Boolean) newValue;
            // Save the main switch state immediately
            mPrefs.edit().putBoolean(HTSR_STATE, isEnabled).apply();
            
            // Always ensure service is running for proper state management
            // The service will handle the actual hardware control based on effective state
            startTouchSamplingServiceOptimized(isEnabled);
            
            // Immediately apply the change if screen is on
            if (isEnabled) {
                // Force immediate activation if user manually enables
                notifyServiceOfImmediateChange("MANUAL_ENABLE");
            } else {
                // Force immediate deactivation if user manually disables
                notifyServiceOfImmediateChange("MANUAL_DISABLE");
            }
            
        } else if ("htsr_auto_screen_control".equals(preference.getKey())) {
            boolean isAutoScreenControl = (Boolean) newValue;
            mPrefs.edit().putBoolean("htsr_auto_screen_control", isAutoScreenControl).apply();
            
            // Ensure service is running to handle screen control
            ensureServiceRunning();
            notifyServiceOfImmediateChange("AUTO_SCREEN_CONTROL_CHANGED");
            
        } else if ("htsr_auto_enable_selected_apps".equals(preference.getKey())) {
            boolean isAutoEnableSelectedApps = (Boolean) newValue;
            mPrefs.edit().putBoolean("htsr_auto_enable_selected_apps", isAutoEnableSelectedApps).apply();
            
            // Ensure service is running to handle auto-app functionality
            ensureServiceRunning();
            notifyServiceOfImmediateChange("AUTO_APP_CHANGED");
        }
        return true;
    }

    private void startTouchSamplingService(boolean enable) {
        Intent serviceIntent = new Intent(getActivity(), TouchSamplingService.class);
        if (enable) {
            getActivity().startService(serviceIntent);
        } else {
            getActivity().stopService(serviceIntent);
        }
    }
    
    /**
     * Optimized service management that considers all features
     */
    private void startTouchSamplingServiceOptimized(boolean mainSwitchEnabled) {
        Intent serviceIntent = new Intent(getActivity(), TouchSamplingService.class);
        
        // Determine if service should be running based on any active feature
        boolean mainEnabled = mainSwitchEnabled;
        boolean autoScreenControl = mPrefs.getBoolean("htsr_auto_screen_control", true);
        boolean autoEnableSelectedApps = mPrefs.getBoolean("htsr_auto_enable_selected_apps", true);
        
        // Service should run if ANY feature is enabled
        boolean shouldRunService = mainEnabled || autoScreenControl || autoEnableSelectedApps;
        
        if (shouldRunService) {
            // Always start service if any feature is active
            getActivity().startService(serviceIntent);
        } else {
            // Only stop service if ALL features are disabled
            getActivity().stopService(serviceIntent);
        }
    }
    
    /**
     * Ensures service is running for background features
     */
    private void ensureServiceRunning() {
        Intent serviceIntent = new Intent(getActivity(), TouchSamplingService.class);
        getActivity().startService(serviceIntent);
    }
    
    /**
     * Notifies the service of immediate preference changes for instant response
     */
    private void notifyServiceOfImmediateChange(String changeType) {
        try {
            Intent serviceIntent = new Intent(getActivity(), TouchSamplingService.class);
            serviceIntent.putExtra("immediate_change", changeType);
            serviceIntent.putExtra("timestamp", System.currentTimeMillis());
            getActivity().startService(serviceIntent);
        } catch (Exception e) {
            android.util.Log.e("TouchSamplingSettings", "Error notifying service of change: " + changeType, e);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            getActivity().onBackPressed();
            return true;
        }
        return false;
    }
}
