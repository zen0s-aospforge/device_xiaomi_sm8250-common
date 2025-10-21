/*
 * Copyright (C) 2015 The CyanogenMod Project
 *               2017-2018 The LineageOS Project
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

package org.lineageos.settings.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Proximity sensor handler for detecting hand gestures and pocket placement.
 * Implements SensorEventListener to monitor proximity sensor changes.
 */
class ProximitySensor(context: Context) : SensorEventListener {

    companion object {
        private const val DEBUG = false
        private const val TAG = "ProximitySensor"

        // Gesture detection constants
        private const val HANDWAVE_MAX_DELTA_NS = 1_000_000_000L // 1 second
        private const val POCKET_MIN_DELTA_NS = 2_000_000_000L  // 2 seconds
    }

    private val sensorManager: SensorManager = context.getSystemService(SensorManager::class.java)
    private val proximitySensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY, false)
    private val executorService: ExecutorService = Executors.newSingleThreadExecutor()

    // Sensor state tracking
    private var sawNear = false
    private var inPocketTime = 0L

    init {
        if (proximitySensor == null) {
            Log.w(TAG, "Proximity sensor not available on this device")
        } else if (DEBUG) {
            Log.d(TAG, "Proximity sensor initialized: ${proximitySensor.name}")
        }
    }

    /**
     * Submit a task to the executor service.
     */
    private fun submit(task: () -> Unit): Future<*> {
        return executorService.submit(task)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        try {
            val isNear = event.values[0] < (proximitySensor?.maximumRange ?: Float.MAX_VALUE)

            // Update pocket detection timing
            if (!sawNear || isNear) {
                inPocketTime = event.timestamp
            }

            sawNear = isNear

            if (DEBUG) {
                Log.d(TAG, "Proximity changed: near=$isNear, timestamp=${event.timestamp}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing proximity sensor event", e)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (DEBUG && sensor != null) {
            Log.d(TAG, "Proximity sensor accuracy changed: ${sensor.name}, accuracy=$accuracy")
        }
    }

    /**
     * Enable proximity sensor listening.
     */
    fun enable() {
        if (DEBUG) Log.d(TAG, "Enabling proximity sensor")

        submit {
            try {
                proximitySensor?.let { sensor ->
                    sensorManager.registerListener(
                        this@ProximitySensor,
                        sensor,
                        SensorManager.SENSOR_DELAY_NORMAL
                    )
                    if (DEBUG) Log.d(TAG, "Proximity sensor listener registered")
                } ?: Log.w(TAG, "Cannot enable proximity sensor - sensor not available")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to enable proximity sensor", e)
            }
        }
    }

    /**
     * Disable proximity sensor listening.
     */
    fun disable() {
        if (DEBUG) Log.d(TAG, "Disabling proximity sensor")

        submit {
            try {
                sensorManager.unregisterListener(this@ProximitySensor, proximitySensor)
                if (DEBUG) Log.d(TAG, "Proximity sensor listener unregistered")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to disable proximity sensor", e)
            }
        }
    }

    /**
     * Check if the sensor has detected something nearby.
     *
     * @return true if proximity sensor is currently detecting near proximity
     */
    fun getSawNear(): Boolean = sawNear

    /**
     * Get the timestamp when the device was last detected as being in a pocket.
     * Based on POCKET_MIN_DELTA_NS threshold.
     *
     * @return timestamp in nanoseconds
     */
    fun getInPocketTime(): Long = inPocketTime

    /**
     * Check if the device is currently considered to be in a pocket.
     * Uses the pocket detection timing threshold.
     *
     * @param currentTimeNs current system time in nanoseconds
     * @return true if device is in pocket based on timing
     */
    fun isInPocket(currentTimeNs: Long): Boolean {
        return sawNear && (currentTimeNs - inPocketTime) >= POCKET_MIN_DELTA_NS
    }

    /**
     * Check if a recent handwave gesture was detected.
     * Uses the handwave timing threshold.
     *
     * @param currentTimeNs current system time in nanoseconds
     * @return true if handwave gesture was detected within threshold
     */
    fun wasHandwaveDetected(currentTimeNs: Long): Boolean {
        return !sawNear && (currentTimeNs - inPocketTime) <= HANDWAVE_MAX_DELTA_NS
    }

    /**
     * Reset the sensor state tracking.
     * Useful for clearing gesture detection state.
     */
    fun reset() {
        sawNear = false
        inPocketTime = 0L
        if (DEBUG) Log.d(TAG, "Proximity sensor state reset")
    }

    /**
     * Check if the proximity sensor is available on this device.
     *
     * @return true if proximity sensor is available
     */
    fun isSensorAvailable(): Boolean = proximitySensor != null

    /**
     * Get the maximum range of the proximity sensor.
     *
     * @return maximum range in cm, or 0.0 if sensor not available
     */
    fun getMaximumRange(): Float = proximitySensor?.maximumRange ?: 0.0f
}