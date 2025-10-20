/*
 * Copyright (C) 2025 The LineageOS Project
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

package org.lineageos.settings.autohbm;

import android.app.KeyguardManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.IBinder;
import android.os.PowerManager;
import androidx.preference.PreferenceManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.lineageos.settings.Constants;
import org.lineageos.settings.utils.FileUtils;

public class AutoHbmService extends Service {

    private static final int MAX_BRIGHTNESS = 4000;
    private static final int FALLBACK_BRIGHTNESS = 200;
    private static boolean mAutoHbmActive = false;
    private ExecutorService mExecutorService;
    private volatile boolean mServiceActive = false;

    private SensorManager mSensorManager;
    private Sensor mLightSensor;

    private SharedPreferences mSharedPrefs;
    private int mLastManualBrightness = FALLBACK_BRIGHTNESS;
    private boolean mIsAutoBrightnessEnabled = false;

    public void activateLightSensorRead() {
        submit(() -> {
            if (mServiceActive && mSensorManager == null) {
                mSensorManager = (SensorManager) getApplicationContext().getSystemService(Context.SENSOR_SERVICE);
                if (mSensorManager != null) {
                    mLightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
                    if (mLightSensor != null) {
                        boolean registered = mSensorManager.registerListener(
                            mSensorEventListener, mLightSensor, SensorManager.SENSOR_DELAY_NORMAL);
                        if (!registered) {
                            android.util.Log.w("AutoHbmService", "Failed to register light sensor listener");
                        }
                    }
                }
            }
        });
    }

    public void deactivateLightSensorRead() {
        submit(() -> {
            if (mSensorManager != null) {
                try {
                    mSensorManager.unregisterListener(mSensorEventListener);
                } catch (Exception e) {
                    android.util.Log.w("AutoHbmService", "Error unregistering sensor listener", e);
                }
                mSensorManager = null;
                mLightSensor = null;
            }
            mAutoHbmActive = false;
            restoreBrightness();
        });
    }

    private void setBrightnessDirectly(int brightness) {
        FileUtils.writeValue(Constants.NODE_BRIGHTNESS, String.valueOf(brightness));
    }

    private void restoreBrightness() {
        if (mIsAutoBrightnessEnabled) {
            // Auto-brightness will handle the adjustment
        } else {
            setBrightnessDirectly(mLastManualBrightness > 0 ? mLastManualBrightness : FALLBACK_BRIGHTNESS);
        }
    }

    private boolean isCurrentlyEnabled() {
        String fileValue = FileUtils.getFileValue(Constants.NODE_BRIGHTNESS, "0");
        return Integer.parseInt(fileValue) == MAX_BRIGHTNESS;
    }

    SensorEventListener mSensorEventListener = new SensorEventListener() {
        private boolean mCrossedThreshold = false;
        private long mCrossedThresholdTime = 0;
        private long mLastTriggerTime = 0;

        @Override
        public void onSensorChanged(SensorEvent event) {
            float lux = event.values[0];
            KeyguardManager km = (KeyguardManager) getSystemService(getApplicationContext().KEYGUARD_SERVICE);
            boolean keyguardShowing = km.inKeyguardRestrictedInputMode();
            int luxThreshold = mSharedPrefs.getInt(Constants.KEY_AUTO_HBM_THRESHOLD, 20000);
            int timeToEnableHbm = mSharedPrefs.getInt(Constants.KEY_AUTO_HBM_ENABLE_TIME, 0);
            int timeToDisableHbm = mSharedPrefs.getInt(Constants.KEY_AUTO_HBM_DISABLE_TIME, 1);

            if (lux > luxThreshold) {
                if (!mCrossedThreshold) {
                    mCrossedThreshold = true;
                    mCrossedThresholdTime = System.currentTimeMillis();
                } else {
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - mCrossedThresholdTime >= timeToEnableHbm * 1000 && (!mAutoHbmActive || !isCurrentlyEnabled()) && !keyguardShowing) {
                        mAutoHbmActive = true;
                        saveCurrentBrightness();
                        setBrightnessDirectly(MAX_BRIGHTNESS);
                        mLastTriggerTime = currentTime;
                    }
                }
            } else {
                mCrossedThreshold = false;

                if (mAutoHbmActive) {
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - mLastTriggerTime >= timeToDisableHbm * 1000) {
                        mAutoHbmActive = false;
                        restoreBrightness();
                    }
                }
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // do nothing
        }
    };

    private void saveCurrentBrightness() {
        try {
            String fileValue = FileUtils.getFileValue(Constants.NODE_BRIGHTNESS, String.valueOf(FALLBACK_BRIGHTNESS));
            mLastManualBrightness = Integer.parseInt(fileValue);
        } catch (NumberFormatException e) {
            mLastManualBrightness = FALLBACK_BRIGHTNESS;
        }
        mIsAutoBrightnessEnabled = mSharedPrefs.getBoolean("auto_brightness", false);
    }

    private BroadcastReceiver mScreenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                activateLightSensorRead();
            } else if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                deactivateLightSensorRead();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mServiceActive = true;
        
        // Use named thread for better debugging
        mExecutorService = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "AutoHbmService-Worker");
            t.setDaemon(true); // Prevent blocking shutdown
            return t;
        });
        
        IntentFilter screenStateFilter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        screenStateFilter.addAction(Intent.ACTION_SCREEN_OFF);
        try {
            registerReceiver(mScreenStateReceiver, screenStateFilter);
        } catch (Exception e) {
            android.util.Log.e("AutoHbmService", "Failed to register screen state receiver", e);
        }
        
        mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && pm.isInteractive()) {
            activateLightSensorRead();
        }
    }

    private Future<?> submit(Runnable runnable) {
        if (mExecutorService != null && !mExecutorService.isShutdown()) {
            try {
                return mExecutorService.submit(runnable);
            } catch (Exception e) {
                android.util.Log.e("AutoHbmService", "Failed to submit task", e);
                return null;
            }
        }
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mServiceActive = false;
        
        // Unregister broadcast receiver
        try {
            unregisterReceiver(mScreenStateReceiver);
        } catch (Exception e) {
            android.util.Log.w("AutoHbmService", "Error unregistering screen state receiver", e);
        }
        
        // Clean up sensor
        deactivateLightSensorRead();
        
        // Shutdown executor service
        if (mExecutorService != null) {
            mExecutorService.shutdown();
            try {
                if (!mExecutorService.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    mExecutorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                mExecutorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
            mExecutorService = null;
        }
        
        // Reset state
        mAutoHbmActive = false;
        mSharedPrefs = null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
