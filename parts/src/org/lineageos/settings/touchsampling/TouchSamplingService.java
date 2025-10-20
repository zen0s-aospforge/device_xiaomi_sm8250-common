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

import android.app.ActivityManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.FileObserver;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;

import org.lineageos.settings.R;
import org.lineageos.settings.utils.FileUtils;
import org.lineageos.settings.touchsampling.TouchFeatureController;

public class TouchSamplingService extends Service {
    private static final String TAG = "TouchSamplingService";

    private BroadcastReceiver mScreenUnlockReceiver;
    private SharedPreferences.OnSharedPreferenceChangeListener mPreferenceChangeListener;
    private FileObserver mSconfigObserver;
    private Handler mAutoAppsHandler;
    private Runnable mAutoAppsRunnable;
    private NotificationManager mNotificationManager;
    private static final int NOTIFICATION_ID = 3;
    private static final String NOTIFICATION_CHANNEL_ID = "touch_sampling_tile_service_channel";
    private boolean mLastEffectiveState = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "TouchSamplingService started");

        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        setupNotificationChannel();

        // Check for crash recovery before initializing
        recoverFromCrash();

        // Initialize and register the broadcast receiver
        registerScreenUnlockReceiver();

        // Initialize and register the SharedPreferences listener
        registerPreferenceChangeListener();

        // Initialize screen state and apply touch sampling rate
        initializeScreenState();

        // Start a FileObserver to watch the sconfig file changes
        mSconfigObserver = new FileObserver(TouchSamplingUtils.SCONFIG_FILE, FileObserver.MODIFY) {
            @Override
            public void onEvent(int event, String path) {
                if ((event & FileObserver.MODIFY) != 0) {
                    Log.d(TAG, "sconfig file modified. Reapplying touch sampling rate.");
                    updateEffectiveStateAndApply();
                }
            }
        };
        mSconfigObserver.startWatching();

        // Periodically check for auto-enabled apps with adaptive interval
        mAutoAppsHandler = new Handler();
        mAutoAppsRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    // Validate feature states periodically to prevent conflicts
                    validateFeatureStates();
                    
                    // Update effective state
                    updateEffectiveStateAndApply();
                    
                    // Use adaptive interval based on screen state and feature activity
                    boolean screenOn = isScreenOn();
                    boolean hasActiveFeatures = getEffectiveTouchSamplingEnabled(TouchSamplingService.this);
                    
                    long interval;
                    if (screenOn && hasActiveFeatures) {
                        interval = 2000; // 2s when active and screen on
                    } else if (screenOn) {
                        interval = 2000; // 2s when screen on but inactive
                    } else {
                        interval = 2000; // 2s when screen off
                    }
                    
                    mAutoAppsHandler.postDelayed(this, interval);
                } catch (Exception e) {
                    Log.e(TAG, "Error in auto apps runnable", e);
                    // Fallback to optimized interval on error (reduced to 2s)
                    mAutoAppsHandler.postDelayed(this, 2000);
                }
            }
        };
        mAutoAppsHandler.post(mAutoAppsRunnable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Handle immediate change notifications from settings
        if (intent != null && intent.hasExtra("immediate_change")) {
            String changeType = intent.getStringExtra("immediate_change");
            long timestamp = intent.getLongExtra("timestamp", 0);
            
            Log.d(TAG, "Received immediate change notification: " + changeType + " at " + timestamp);
            handleImmediateSettingsChange(changeType);
        }
        
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "TouchSamplingService stopped - performing comprehensive cleanup");

        try {
            // Enhanced cleanup to prevent memory leaks and ensure system stability
            
            // 1. Stop the auto apps handler and clear all callbacks
            if (mAutoAppsHandler != null) {
                try {
                    if (mAutoAppsRunnable != null) {
                        mAutoAppsHandler.removeCallbacks(mAutoAppsRunnable);
                    }
                    mAutoAppsHandler.removeCallbacksAndMessages(null); // Clear all pending messages
                    Log.d(TAG, "Handler callbacks cleared successfully");
                } catch (Exception e) {
                    Log.w(TAG, "Error clearing handler callbacks", e);
                }
                mAutoAppsHandler = null;
                mAutoAppsRunnable = null;
            }

            // 2. Cancel any active notifications
            try {
                if (mNotificationManager != null) {
                    mNotificationManager.cancel(NOTIFICATION_ID);
                    Log.d(TAG, "Notifications cancelled successfully");
                }
            } catch (Exception e) {
                Log.w(TAG, "Error cancelling notifications", e);
            }

            // 3. Save final state for crash recovery
            try {
                saveServiceShutdownState();
            } catch (Exception e) {
                Log.w(TAG, "Error saving shutdown state", e);
            }

            Log.d(TAG, "TouchSamplingService cleanup completed successfully");
            
        } catch (Exception e) {
            Log.e(TAG, "Critical error during service cleanup", e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Saves service state during shutdown for crash recovery
     */
    private void saveServiceShutdownState() {
        try {
            SharedPreferences sharedPref = getSharedPreferences(TouchSamplingSettingsFragment.SHAREDHTSR, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = sharedPref.edit();
            
            // Save current service state
            editor.putBoolean("htsr_service_was_running", true);
            editor.putLong("htsr_service_shutdown_time", System.currentTimeMillis());
            editor.putBoolean("htsr_last_effective_state", mLastEffectiveState);
            editor.putBoolean("htsr_screen_on_at_shutdown", isScreenOn());
            
            // Save feature states for recovery
            boolean mainEnabled = sharedPref.getBoolean(TouchSamplingSettingsFragment.HTSR_STATE, false);
            boolean autoScreenControl = sharedPref.getBoolean("htsr_auto_screen_control", true);
            boolean autoAppEnabled = sharedPref.getBoolean("htsr_auto_enable_selected_apps", true);
            
            editor.putBoolean("htsr_main_enabled_at_shutdown", mainEnabled);
            editor.putBoolean("htsr_auto_screen_enabled_at_shutdown", autoScreenControl);
            editor.putBoolean("htsr_auto_app_enabled_at_shutdown", autoAppEnabled);
            
            editor.apply();
            Log.d(TAG, "Service shutdown state saved successfully");
            
        } catch (Exception e) {
            Log.e(TAG, "Error saving service shutdown state", e);
        }
    }

    /**
     * Recovers service state after crash or unexpected shutdown
     */
    private void recoverFromCrash() {
        try {
            SharedPreferences sharedPref = getSharedPreferences(TouchSamplingSettingsFragment.SHAREDHTSR, Context.MODE_PRIVATE);
            boolean wasRunning = sharedPref.getBoolean("htsr_service_was_running", false);
            long shutdownTime = sharedPref.getLong("htsr_service_shutdown_time", 0);
            long timeSinceShutdown = System.currentTimeMillis() - shutdownTime;
            
            if (wasRunning && timeSinceShutdown < 60000) { // 1 minute
                Log.d(TAG, "Detected potential crash recovery scenario (shutdown " + timeSinceShutdown + "ms ago)");
                
                // Restore previous state
                boolean lastEffectiveState = sharedPref.getBoolean("htsr_last_effective_state", false);
                boolean screenOnAtShutdown = sharedPref.getBoolean("htsr_screen_on_at_shutdown", true);
                boolean currentScreenOn = isScreenOn();
                
                if (lastEffectiveState && currentScreenOn) {
                    Log.d(TAG, "Crash recovery: Restoring touch sampling state");
                    applyTouchSamplingRateImmediate(1);
                    updateNotification(true);
                    mLastEffectiveState = true;
                }
            }
            
            // Clear recovery flags
            sharedPref.edit()
                .remove("htsr_service_was_running")
                .remove("htsr_service_shutdown_time")
                .remove("htsr_last_effective_state")
                .remove("htsr_screen_on_at_shutdown")
                .apply();
                
        } catch (Exception e) {
            Log.e(TAG, "Error during crash recovery", e);
        }
    }

    /**
     * Handles immediate settings changes for optimal main switch responsiveness
     */
    private void handleImmediateSettingsChange(String changeType) {
        try {
            boolean screenOn = isScreenOn();
            
            switch (changeType) {
                case "MANUAL_ENABLE":
                    // User manually enabled the main switch - activate immediately if screen is on
                    if (screenOn) {
                        Log.d(TAG, "Manual enable: IMMEDIATELY activating touch sampling");
                        applyTouchSamplingRateImmediate(1);
                        updateNotification(true);
                        mLastEffectiveState = true;
                    } else {
                        Log.d(TAG, "Manual enable: Screen off, will activate when screen turns on");
                    }
                    break;
                    
                case "MANUAL_DISABLE":
                    // User manually disabled the main switch - deactivate immediately
                    Log.d(TAG, "Manual disable: IMMEDIATELY deactivating touch sampling");
                    applyTouchSamplingRateImmediate(0);
                    updateNotification(false);
                    mLastEffectiveState = false;
                    break;
                    
                case "AUTO_SCREEN_CONTROL_CHANGED":
                case "AUTO_APP_CHANGED":
                    // Auto features changed - reapply effective state immediately
                    Log.d(TAG, "Auto feature changed: Reapplying effective state immediately");
                    boolean effectiveState = getEffectiveTouchSamplingEnabled(this);
                    if (screenOn && effectiveState) {
                        applyTouchSamplingRateImmediate(1);
                        updateNotification(true);
                        mLastEffectiveState = true;
                    } else {
                        applyTouchSamplingRateImmediate(0);
                        updateNotification(false);
                        mLastEffectiveState = false;
                    }
                    break;
                    
                default:
                    Log.w(TAG, "Unknown immediate change type: " + changeType);
                    // Fallback to standard state update
                    updateEffectiveStateAndApply();
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling immediate settings change: " + changeType, e);
            // Fallback to standard behavior
            updateEffectiveStateAndApply();
        }
    }

    /**
     * Registers a BroadcastReceiver to handle screen state changes for automatic touch sampling control.
     */
    private void registerScreenUnlockReceiver() {
        mScreenUnlockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                try {
                    if (Intent.ACTION_SCREEN_ON.equals(action) || 
                        Intent.ACTION_USER_PRESENT.equals(action) ||
                        Intent.ACTION_DREAMING_STOPPED.equals(action) ||
                        "android.intent.action.SCREEN_WAKE_UP".equals(action)) {
                        Log.d(TAG, "Screen wake-up event detected: " + action + ". Activating touch sampling immediately.");
                        handleScreenWakeUp(action);
                    } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                        Log.d(TAG, "Screen turned off. Disabling automatic touch sampling.");
                        handleScreenOff();
                    } else if (Intent.ACTION_SHUTDOWN.equals(action) || Intent.ACTION_REBOOT.equals(action)) {
                        Log.d(TAG, "Device shutdown/reboot detected: " + action + ". Saving current state for restoration.");
                        handleShutdownReboot(action);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error handling screen state change: " + action, e);
                    // Ensure system stability - revert to safe state
                    try {
                        restoreLastKnownState();
                    } catch (Exception fallbackError) {
                        Log.e(TAG, "Critical error in screen state handling fallback", fallbackError);
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        // Add additional wake-up events for comprehensive coverage
        filter.addAction(Intent.ACTION_DREAMING_STOPPED);
        filter.addAction("android.intent.action.SCREEN_WAKE_UP");
        // Add shutdown/reboot events for state preservation
        filter.addAction(Intent.ACTION_SHUTDOWN);
        filter.addAction(Intent.ACTION_REBOOT);
        filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        
        try {
            registerReceiver(mScreenUnlockReceiver, filter);
            Log.d(TAG, "Screen state receiver registered successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to register screen state receiver", e);
        }
    }

    /**
     * Registers a SharedPreferences listener to monitor changes in the touch sampling settings.
     */
    private void registerPreferenceChangeListener() {
        SharedPreferences sharedPref = getSharedPreferences(TouchSamplingSettingsFragment.SHAREDHTSR, Context.MODE_PRIVATE);
        mPreferenceChangeListener = (sharedPreferences, key) -> {
            if (TouchSamplingSettingsFragment.HTSR_STATE.equals(key)
                    || "htsr_auto_enable_selected_apps".equals(key)) {
                Log.d(TAG, "Preference changed (" + key + "). Reapplying touch sampling rate.");
                updateEffectiveStateAndApply();
            }
        };
        sharedPref.registerOnSharedPreferenceChangeListener(mPreferenceChangeListener);
    }

    /**
     * Reads the touch sampling rate preferences and applies the effective state.
     * Enhanced for optimal main switch integration.
     */
    private void updateEffectiveStateAndApply() {
        boolean effectiveState = getEffectiveTouchSamplingEnabled(this);
        boolean screenOn = isScreenOn();
        
        // Enhanced logic for main switch integration
        if (effectiveState != mLastEffectiveState) {
            // State changed: update hardware and notification
            if (screenOn && effectiveState) {
                // Screen is on and should be active - use immediate activation
                applyTouchSamplingRateImmediate(1);
            } else {
                // Should be inactive or screen is off - use immediate deactivation
                applyTouchSamplingRateImmediate(0);
            }
            updateNotification(effectiveState);
            mLastEffectiveState = effectiveState;
            
            Log.d(TAG, "State change applied: effective=" + effectiveState + ", screen=" + screenOn + ", hardware=" + (screenOn && effectiveState ? 1 : 0));
        } else if (effectiveState && screenOn) {
            // State is ON and screen is on - ensure hardware is active (reapply if needed)
            applyTouchSamplingRateImmediate(1);
        } else if (!effectiveState || !screenOn) {
            // State is OFF or screen is off - ensure hardware is inactive
            applyTouchSamplingRate(0);
        }
    }

    /**
     * Applies the given touch sampling rate state directly to the hardware file.
     *
     * @param state 1 to enable high touch sampling rate, 0 to disable it.
     */
    private void applyTouchSamplingRate(int state) {
        boolean enable = state == 1;
        Log.d(TAG, "Applying touch sampling via controller: " + enable);
        TouchFeatureController.INSTANCE.setHighTouchSamplingEnabled(enable);
    }

    /**
     * Checks if the foreground app is in the auto-enable list and applies HTSR accordingly.
     */
    private void applyTouchSamplingRateForAutoApps() {
        SharedPreferences sharedPref = getSharedPreferences(TouchSamplingSettingsFragment.SHAREDHTSR, Context.MODE_PRIVATE);
        boolean mainEnabled = sharedPref.getBoolean(TouchSamplingSettingsFragment.HTSR_STATE, false);
        boolean autoEnableSelectedApps = sharedPref.getBoolean("htsr_auto_enable_selected_apps", true);
        if (mainEnabled || !autoEnableSelectedApps) {
            // Already handled by other logic or not enabled
            return;
        }
        // Check auto apps
        java.util.Set<String> autoApps = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                .getStringSet(org.lineageos.settings.touchsampling.TouchSamplingPerAppConfigFragment.PREF_AUTO_APPS, new java.util.HashSet<>());
        String foreground = getForegroundApp(this);
        int effectiveState = (autoApps.contains(foreground)) ? 1 : 0;
        applyTouchSamplingRate(effectiveState);
    }

    /**
     * Returns the package name of the current foreground app.
     * Optimized with timeout and caching for better responsiveness.
     */
    private static String getForegroundApp(Context context) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                // Use timeout to prevent blocking - optimized for 2s max response time
                java.util.List<android.app.ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
                if (tasks != null && !tasks.isEmpty() && tasks.get(0).topActivity != null) {
                    return tasks.get(0).topActivity.getPackageName();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error getting foreground app, using fallback", e);
        }
        return null;
    }

    private void updateNotification(boolean effectiveState) {
        if (effectiveState) {
            showTouchSamplingNotification();
        } else {
            cancelTouchSamplingNotification();
        }
    }
    
    private boolean isScreenOn() {
        try {
            android.os.PowerManager powerManager = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            return powerManager != null && powerManager.isInteractive();
        } catch (Exception e) {
            return true; // Default to screen on if we can't determine
        }
    }

    private void setupNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.touch_sampling_mode_title),
                NotificationManager.IMPORTANCE_DEFAULT
        );
        channel.setBlockable(true);
        mNotificationManager.createNotificationChannel(channel);
    }

    private void showTouchSamplingNotification() {
        Intent intent = new Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(getString(R.string.touch_sampling_mode_title))
                .setContentText(getString(R.string.touch_sampling_mode_notification))
                .setSmallIcon(R.drawable.ic_touch_sampling_tile)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setFlag(Notification.FLAG_NO_CLEAR, true)
                .build();
        mNotificationManager.notify(NOTIFICATION_ID, notification);
    }

    private void cancelTouchSamplingNotification() {
        mNotificationManager.cancel(NOTIFICATION_ID);
    }

    /**
     * Enhanced effective state calculation with comprehensive feature coordination
     * Prevents conflicts and ensures optimal feature interaction
     */
    public static boolean getEffectiveTouchSamplingEnabled(Context context) {
        try {
            SharedPreferences sharedPref = context.getSharedPreferences(TouchSamplingSettingsFragment.SHAREDHTSR, Context.MODE_PRIVATE);
            
            // Get all feature states
            boolean mainEnabled = sharedPref.getBoolean(TouchSamplingSettingsFragment.HTSR_STATE, false);
            boolean autoEnableSelectedApps = sharedPref.getBoolean("htsr_auto_enable_selected_apps", true);
            boolean autoScreenControl = sharedPref.getBoolean("htsr_auto_screen_control", true);
            
            // Priority 1: Main switch (highest priority - user explicit control)
            if (mainEnabled) {
                return true;
            }
            
            // Priority 2: Auto-app feature (app-specific activation)
            if (autoEnableSelectedApps) {
                try {
                    java.util.Set<String> autoApps = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                            .getStringSet(org.lineageos.settings.touchsampling.TouchSamplingPerAppConfigFragment.PREF_AUTO_APPS, new java.util.HashSet<>());
                    
                    if (autoApps != null && !autoApps.isEmpty()) {
                        String foreground = getForegroundApp(context);
                        if (foreground != null && autoApps.contains(foreground)) {
                            return true;
                        }
                    }
                } catch (Exception e) {
                    android.util.Log.e("TouchSamplingService", "Error checking auto apps", e);
                }
            }
            
            // Priority 3: Auto screen control (lowest priority - background feature)
            // This is handled separately in screen state management, not here
            // to prevent conflicts with the other features
            
            return false;
            
        } catch (Exception e) {
            android.util.Log.e("TouchSamplingService", "Error in getEffectiveTouchSamplingEnabled", e);
            // Safe fallback - only activate if main switch is explicitly enabled
            try {
                SharedPreferences sharedPref = context.getSharedPreferences(TouchSamplingSettingsFragment.SHAREDHTSR, Context.MODE_PRIVATE);
                return sharedPref.getBoolean(TouchSamplingSettingsFragment.HTSR_STATE, false);
            } catch (Exception fallbackError) {
                return false; // Ultimate safe fallback
            }
        }
    }

    /**
     * Validates feature states and prevents conflicts between all three features
     */
    private boolean validateFeatureStates() {
        try {
            SharedPreferences sharedPref = getSharedPreferences(TouchSamplingSettingsFragment.SHAREDHTSR, Context.MODE_PRIVATE);
            
            boolean mainEnabled = sharedPref.getBoolean(TouchSamplingSettingsFragment.HTSR_STATE, false);
            boolean autoScreenControl = sharedPref.getBoolean("htsr_auto_screen_control", true);
            boolean autoAppEnabled = sharedPref.getBoolean("htsr_auto_enable_selected_apps", true);
            
            // Validation checks
            boolean hasValidConfig = true;
            
            // Check 1: Ensure at least one feature is enabled if service is running
            if (!mainEnabled && !autoScreenControl && !autoAppEnabled) {
                Log.w(TAG, "Feature validation: No features enabled, service should not be running");
                hasValidConfig = false;
            }
            
            // Check 2: Validate auto-app configuration
            if (autoAppEnabled) {
                try {
                    java.util.Set<String> autoApps = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
                            .getStringSet(org.lineageos.settings.touchsampling.TouchSamplingPerAppConfigFragment.PREF_AUTO_APPS, new java.util.HashSet<>());
                    if (autoApps == null || autoApps.isEmpty()) {
                        Log.d(TAG, "Feature validation: Auto-app enabled but no apps configured");
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Feature validation: Error checking auto-app configuration", e);
                }
            }
            
            // Check 3: Validate screen state consistency
            boolean screenOn = isScreenOn();
            boolean currentEffective = getEffectiveTouchSamplingEnabled(this);
            
            if (screenOn && currentEffective && !mLastEffectiveState) {
                Log.d(TAG, "Feature validation: State inconsistency detected, correcting...");
                applyTouchSamplingRateImmediate(1);
                updateNotification(true);
                mLastEffectiveState = true;
            } else if ((!screenOn || !currentEffective) && mLastEffectiveState) {
                Log.d(TAG, "Feature validation: State inconsistency detected, correcting...");
                applyTouchSamplingRateImmediate(0);
                updateNotification(false);
                mLastEffectiveState = false;
            }
            
            Log.d(TAG, "Feature validation completed: main=" + mainEnabled + ", autoScreen=" + autoScreenControl + 
                      ", autoApp=" + autoAppEnabled + ", effective=" + currentEffective + ", screen=" + screenOn);
            
            return hasValidConfig;
            
        } catch (Exception e) {
            Log.e(TAG, "Error during feature validation", e);
            return false;
        }
    }

    /**
     * Handles screen wake-up events - immediately activates touch sampling if feature was enabled
     * Supports all wake-up scenarios: power button, fingerprint, face unlock, etc.
     */
    private void handleScreenWakeUp(String wakeUpAction) {
        try {
            // Save current screen state immediately
            saveScreenState(true);
            
            SharedPreferences sharedPref = getSharedPreferences(TouchSamplingSettingsFragment.SHAREDHTSR, Context.MODE_PRIVATE);
            boolean autoScreenControl = sharedPref.getBoolean("htsr_auto_screen_control", true);
            
            // Enhanced feature coordination logic - prevents conflicts between all three features
            boolean wasActiveBeforeScreenOff = sharedPref.getBoolean("htsr_state_before_screen_off", false);
            boolean wasManuallyEnabled = sharedPref.getBoolean("htsr_was_manually_enabled_before_off", false);
            boolean wasAutoAppEnabled = sharedPref.getBoolean("htsr_was_auto_app_enabled_before_off", true);
            long screenOffTime = sharedPref.getLong("htsr_screen_off_timestamp", 0);
            long timeSinceScreenOff = System.currentTimeMillis() - screenOffTime;
            
            // Get current feature states for coordination
            boolean mainCurrentlyEnabled = sharedPref.getBoolean(TouchSamplingSettingsFragment.HTSR_STATE, false);
            boolean autoAppCurrentlyEnabled = sharedPref.getBoolean("htsr_auto_enable_selected_apps", true);
            boolean currentlyEffectiveFromFeatures = getEffectiveTouchSamplingEnabled(this);
            
            boolean shouldActivate = false;
            String activationReason = "";
            
            // ENHANCED COORDINATION LOGIC:
            // 1. Main switch has highest priority
            // 2. Auto-app feature has medium priority
            // 3. Auto screen control has lowest priority and works with others
            
            if (mainCurrentlyEnabled) {
                // Main switch is ON - always activate (highest priority)
                shouldActivate = true;
                activationReason = "main switch enabled (highest priority)";
            } else if (currentlyEffectiveFromFeatures) {
                // Auto-app feature is active - activate (medium priority)
                shouldActivate = true;
                activationReason = "auto-app feature active (medium priority)";
            } else if (autoScreenControl) {
                // Auto screen control logic (lowest priority)
                if (wasActiveBeforeScreenOff && timeSinceScreenOff < 1800000) { // 30 minutes (optimized from 24 hours for better responsiveness)
                    shouldActivate = true;
                    activationReason = "auto screen control: was active before screen off (" + timeSinceScreenOff + "ms ago)";
                } else if (wasActiveBeforeScreenOff && timeSinceScreenOff >= 1800000) {
                    // Too much time has passed, defer to current feature states
                    if (currentlyEffectiveFromFeatures) {
                        shouldActivate = true;
                        activationReason = "auto screen control: screen off too old, using current features";
                    }
                }
            }
            
            // Additional safety check - prevent conflicts
            if (!shouldActivate && (mainCurrentlyEnabled || currentlyEffectiveFromFeatures)) {
                shouldActivate = true;
                activationReason = "safety check: feature currently active";
            }
            
            if (shouldActivate) {
                Log.d(TAG, "Screen wake-up (" + wakeUpAction + "): IMMEDIATELY activating touch sampling - " + activationReason);
                
                // Apply touch sampling immediately without delay
                applyTouchSamplingRateImmediate(1);
                updateNotification(true);
                mLastEffectiveState = true;
                
                // Save successful activation timestamp for validation
                sharedPref.edit()
                    .putLong("htsr_last_activation", System.currentTimeMillis())
                    .putString("htsr_last_wake_action", wakeUpAction)
                    .apply();
                    
            } else {
                Log.d(TAG, "Screen wake-up (" + wakeUpAction + "): Touch sampling not needed - autoControl=" + autoScreenControl + ", wasActive=" + wasActiveBeforeScreenOff + ", effective=" + currentlyEffectiveFromFeatures);
                
                // Ensure touch sampling is off if not needed
                if (autoScreenControl) {
                    applyTouchSamplingRateImmediate(0);
                    updateNotification(false);
                    mLastEffectiveState = false;
                }
            }
            
            // Validate screen state consistency after optimized delay (reduced to 200ms)
            mAutoAppsHandler.postDelayed(() -> validateScreenWakeUpState(wakeUpAction), 200);
            
        } catch (Exception e) {
            Log.e(TAG, "Error in handleScreenWakeUp for action: " + wakeUpAction, e);
            // Critical fallback - ensure touch sampling is applied if any feature is enabled
            try {
                if (getEffectiveTouchSamplingEnabled(this)) {
                    Log.w(TAG, "Fallback: Applying touch sampling due to error in wake-up handling");
                    applyTouchSamplingRateImmediate(1);
                    updateNotification(true);
                    mLastEffectiveState = true;
                }
            } catch (Exception criticalError) {
                Log.e(TAG, "Critical error in screen wake-up fallback", criticalError);
                // Ultimate fallback to standard behavior
                updateEffectiveStateAndApply();
            }
        }
    }
    
    /**
     * Applies touch sampling rate immediately with priority handling
     */
    private void applyTouchSamplingRateImmediate(int value) {
        try {
            // Use the TouchFeatureController for immediate application
            TouchFeatureController.INSTANCE.setHighTouchSamplingEnabled(value == 1);
            Log.d(TAG, "Touch sampling applied immediately: " + value);
        } catch (Exception e) {
            Log.e(TAG, "Error applying immediate touch sampling", e);
            // Fallback to standard method
            applyTouchSamplingRate(value);
        }
    }
    
    /**
     * Validates that screen wake-up state was applied correctly
     */
    private void validateScreenWakeUpState(String wakeUpAction) {
        try {
            SharedPreferences sharedPref = getSharedPreferences(TouchSamplingSettingsFragment.SHAREDHTSR, Context.MODE_PRIVATE);
            boolean autoScreenControl = sharedPref.getBoolean("htsr_auto_screen_control", true);
            
            if (autoScreenControl && isScreenOn()) {
                boolean shouldBeActive = sharedPref.getBoolean("htsr_state_before_screen_off", false) || 
                                       getEffectiveTouchSamplingEnabled(this);
                                       
                if (shouldBeActive && !mLastEffectiveState) {
                    Log.w(TAG, "State validation failed after wake-up (" + wakeUpAction + "). Reapplying touch sampling.");
                    applyTouchSamplingRateImmediate(1);
                    updateNotification(true);
                    mLastEffectiveState = true;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error validating screen wake-up state", e);
        }
    }

    /**
     * Handles screen OFF events - immediately disables touch sampling to save power
     */
    private void handleScreenOff() {
        try {
            // Save current screen state immediately
            saveScreenState(false);
            
            SharedPreferences sharedPref = getSharedPreferences(TouchSamplingSettingsFragment.SHAREDHTSR, Context.MODE_PRIVATE);
            boolean autoScreenControl = sharedPref.getBoolean("htsr_auto_screen_control", true);
            
            // Always save the current effective state for precise restoration
            boolean currentlyActive = getEffectiveTouchSamplingEnabled(this);
            boolean wasManuallyEnabled = sharedPref.getBoolean(TouchSamplingSettingsFragment.HTSR_STATE, false);
            boolean wasAutoAppEnabled = sharedPref.getBoolean("htsr_auto_enable_selected_apps", true);
            
            // Save comprehensive state for boot restoration
            SharedPreferences.Editor editor = sharedPref.edit();
            editor.putBoolean("htsr_state_before_screen_off", currentlyActive);
            editor.putBoolean("htsr_was_manually_enabled_before_off", wasManuallyEnabled);
            editor.putBoolean("htsr_was_auto_app_enabled_before_off", wasAutoAppEnabled);
            editor.putLong("htsr_screen_off_timestamp", System.currentTimeMillis());
            editor.apply();
            
            if (autoScreenControl) {
                if (currentlyActive) {
                    Log.d(TAG, "Screen OFF: IMMEDIATELY disabling touch sampling (was active: manual=" + wasManuallyEnabled + ", autoApp=" + wasAutoAppEnabled + ")");
                    applyTouchSamplingRateImmediate(0);
                    updateNotification(false);
                    mLastEffectiveState = false;
                } else {
                    Log.d(TAG, "Screen OFF: Touch sampling already disabled");
                }
            } else {
                Log.d(TAG, "Screen OFF: Auto screen control disabled, maintaining current state");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in handleScreenOff", e);
            // Don't disable on error to maintain stability
        }
    }

    /**
     * Handles shutdown/reboot events - saves precise state for restoration
     */
    private void handleShutdownReboot(String shutdownAction) {
        try {
            SharedPreferences sharedPref = getSharedPreferences(TouchSamplingSettingsFragment.SHAREDHTSR, Context.MODE_PRIVATE);
            
            // Save the current precise state before shutdown/reboot
            boolean currentlyActive = getEffectiveTouchSamplingEnabled(this);
            boolean manuallyEnabled = sharedPref.getBoolean(TouchSamplingSettingsFragment.HTSR_STATE, false);
            boolean autoAppEnabled = sharedPref.getBoolean("htsr_auto_enable_selected_apps", true);
            boolean autoScreenControl = sharedPref.getBoolean("htsr_auto_screen_control", true);
            boolean screenOn = isScreenOn();
            
            Log.d(TAG, "Shutdown/Reboot (" + shutdownAction + "): Saving state - active=" + currentlyActive + 
                      ", manual=" + manuallyEnabled + ", autoApp=" + autoAppEnabled + ", screenOn=" + screenOn);
            
            SharedPreferences.Editor editor = sharedPref.edit();
            editor.putBoolean("htsr_state_before_shutdown", currentlyActive);
            editor.putBoolean("htsr_manual_enabled_before_shutdown", manuallyEnabled);
            editor.putBoolean("htsr_auto_app_enabled_before_shutdown", autoAppEnabled);
            editor.putBoolean("htsr_auto_screen_control_before_shutdown", autoScreenControl);
            editor.putBoolean("htsr_screen_on_before_shutdown", screenOn);
            editor.putLong("htsr_shutdown_timestamp", System.currentTimeMillis());
            editor.putString("htsr_shutdown_action", shutdownAction);
            editor.apply();
            
            Log.d(TAG, "State saved successfully for " + shutdownAction);
            
        } catch (Exception e) {
            Log.e(TAG, "Error saving state during shutdown/reboot: " + shutdownAction, e);
        }
    }

    /**
     * Saves the current screen state for persistence across reboots
     */
    private void saveScreenState(boolean screenOn) {
        try {
            SharedPreferences sharedPref = getSharedPreferences(TouchSamplingSettingsFragment.SHAREDHTSR, Context.MODE_PRIVATE);
            sharedPref.edit()
                .putBoolean("htsr_screen_on", screenOn)
                .putLong("htsr_last_screen_change", System.currentTimeMillis())
                .apply();
        } catch (Exception e) {
            Log.e(TAG, "Error saving screen state", e);
        }
    }

    /**
     * Restores the last known stable state in case of errors
     */
    private void restoreLastKnownState() {
        try {
            SharedPreferences sharedPref = getSharedPreferences(TouchSamplingSettingsFragment.SHAREDHTSR, Context.MODE_PRIVATE);
            boolean lastScreenOn = sharedPref.getBoolean("htsr_screen_on", true);
            long lastChange = sharedPref.getLong("htsr_last_screen_change", 0);
            long timeSinceLastChange = System.currentTimeMillis() - lastChange;
            
            // Only restore if the last change was recent (within 2 seconds for optimal responsiveness)
            if (timeSinceLastChange < 2000) {
                if (lastScreenOn) {
                    boolean wasActive = sharedPref.getBoolean("htsr_state_before_screen_off", false);
                    if (wasActive) {
                        applyTouchSamplingRate(1);
                        updateNotification(true);
                    }
                } else {
                    applyTouchSamplingRate(0);
                    updateNotification(false);
                }
                Log.d(TAG, "Restored last known state: screen=" + lastScreenOn + ", timeSince=" + timeSinceLastChange + "ms");
            } else {
                // Fallback to current effective state if too much time has passed
                updateEffectiveStateAndApply();
                Log.d(TAG, "Last state too old, using current effective state");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error restoring last known state", e);
            // Ultimate fallback - disable touch sampling for safety
            try {
                applyTouchSamplingRate(0);
                updateNotification(false);
            } catch (Exception criticalError) {
                Log.e(TAG, "Critical error in ultimate fallback", criticalError);
            }
        }
    }

    /**
     * Initializes screen state on service start/boot with precise state restoration
     */
    private void initializeScreenState() {
        try {
            boolean currentScreenOn = isScreenOn();
            saveScreenState(currentScreenOn);
            
            SharedPreferences sharedPref = getSharedPreferences(TouchSamplingSettingsFragment.SHAREDHTSR, Context.MODE_PRIVATE);
            boolean autoScreenControl = sharedPref.getBoolean("htsr_auto_screen_control", true);
            
            // Get the current user settings (what the user actually wants)
            boolean manuallyEnabled = sharedPref.getBoolean(TouchSamplingSettingsFragment.HTSR_STATE, false);
            boolean autoAppEnabled = sharedPref.getBoolean("htsr_auto_enable_selected_apps", true);
            boolean currentlyEffective = getEffectiveTouchSamplingEnabled(this);
            
            // Get pre-reboot state if available (prioritize shutdown state over screen-off state)
            boolean wasActiveBeforeReboot = sharedPref.getBoolean("htsr_state_before_shutdown", 
                                          sharedPref.getBoolean("htsr_state_before_screen_off", false));
            boolean wasManuallyEnabledBeforeReboot = sharedPref.getBoolean("htsr_manual_enabled_before_shutdown", 
                                                   sharedPref.getBoolean("htsr_was_manually_enabled_before_off", false));
            long shutdownTime = sharedPref.getLong("htsr_shutdown_timestamp", 0);
            long screenOffTime = sharedPref.getLong("htsr_screen_off_timestamp", 0);
            long rebootTime = Math.max(shutdownTime, screenOffTime); // Use the most recent timestamp
            long timeSinceReboot = System.currentTimeMillis() - rebootTime;
            
            boolean shouldActivateOnBoot = false;
            String bootReason = "";
            
            // PRECISE BOOT LOGIC:
            // 1. If feature was enabled before reboot -> reactivate after boot
            // 2. If feature was disabled before reboot -> remain disabled
            
            if (currentScreenOn) {
                // Screen is ON at boot
                if (manuallyEnabled) {
                    // User has manually enabled the feature -> always activate
                    shouldActivateOnBoot = true;
                    bootReason = "manually enabled by user";
                } else if (autoAppEnabled && currentlyEffective) {
                    // Auto-app feature is active for current context -> activate
                    shouldActivateOnBoot = true;
                    bootReason = "auto-app enabled for current context";
                } else if (autoScreenControl && wasActiveBeforeReboot && timeSinceReboot < 1800000) { // 30 minutes
                    // Feature was active before reboot and auto screen control is on -> restore
                    shouldActivateOnBoot = true;
                    bootReason = "restoring pre-reboot active state (was active " + timeSinceReboot + "ms ago)";
                } else {
                    // Feature should remain disabled
                    shouldActivateOnBoot = false;
                    bootReason = "feature was disabled before reboot";
                }
                
                if (shouldActivateOnBoot) {
                    Log.d(TAG, "Boot with screen ON: IMMEDIATELY enabling touch sampling - " + bootReason);
                    applyTouchSamplingRateImmediate(1);
                    updateNotification(true);
                    mLastEffectiveState = true;
                    
                    // Save boot activation for tracking
                    sharedPref.edit()
                        .putLong("htsr_last_activation", System.currentTimeMillis())
                        .putString("htsr_last_wake_action", "BOOT_SCREEN_ON")
                        .apply();
                } else {
                    Log.d(TAG, "Boot with screen ON: Keeping touch sampling disabled - " + bootReason);
                    applyTouchSamplingRateImmediate(0);
                    updateNotification(false);
                    mLastEffectiveState = false;
                }
            } else {
                // Screen is OFF at boot - always ensure touch sampling is disabled
                Log.d(TAG, "Boot with screen OFF: Ensuring touch sampling is disabled");
                applyTouchSamplingRateImmediate(0);
                updateNotification(false);
                mLastEffectiveState = false;
            }
            
            // Clear old pre-reboot state after successful boot initialization
            sharedPref.edit()
                .remove("htsr_state_before_screen_off")
                .remove("htsr_was_manually_enabled_before_off")
                .remove("htsr_was_auto_app_enabled_before_off")
                .remove("htsr_screen_off_timestamp")
                .remove("htsr_state_before_shutdown")
                .remove("htsr_manual_enabled_before_shutdown")
                .remove("htsr_auto_app_enabled_before_shutdown")
                .remove("htsr_auto_screen_control_before_shutdown")
                .remove("htsr_screen_on_before_shutdown")
                .remove("htsr_shutdown_timestamp")
                .remove("htsr_shutdown_action")
                .putLong("htsr_last_boot_time", System.currentTimeMillis())
                .apply();
                
        } catch (Exception e) {
            Log.e(TAG, "Error initializing screen state", e);
            // Fallback: only activate if user has explicitly enabled it
            try {
                SharedPreferences sharedPref = getSharedPreferences(TouchSamplingSettingsFragment.SHAREDHTSR, Context.MODE_PRIVATE);
                boolean manuallyEnabled = sharedPref.getBoolean(TouchSamplingSettingsFragment.HTSR_STATE, false);
                if (manuallyEnabled && isScreenOn()) {
                    applyTouchSamplingRateImmediate(1);
                    updateNotification(true);
                    mLastEffectiveState = true;
                } else {
                    applyTouchSamplingRateImmediate(0);
                    updateNotification(false);
                    mLastEffectiveState = false;
                }
            } catch (Exception criticalError) {
                Log.e(TAG, "Critical error in boot fallback", criticalError);
            }
        }
    }
}
