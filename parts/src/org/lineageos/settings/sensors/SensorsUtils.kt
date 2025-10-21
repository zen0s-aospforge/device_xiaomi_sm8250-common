/*
 * Copyright (C) 2015 The CyanogenMod Project
 *               2020 The LineageOS Project
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

import android.hardware.Sensor
import android.hardware.SensorManager
import android.util.Log

/**
 * Utility class for Android sensor operations with improved null safety and modern Kotlin features.
 * Updated to fix JVM signature conflicts.
 */
object SensorsUtils {

    private const val TAG = "SensorsUtils"

    /**
     * Find a sensor by its string type from the sensor manager.
     * Uses modern Kotlin collection operations for better performance and readability.
     *
     * @param sensorManager the sensor manager to query
     * @param type the string type of the sensor to find
     * @return the sensor if found, null otherwise
     */
    fun getSensor(sensorManager: SensorManager?, type: String?): Sensor? {
        if (sensorManager == null) {
            Log.w(TAG, "SensorManager is null")
            return null
        }

        if (type.isNullOrBlank()) {
            Log.w(TAG, "Sensor type is null or blank")
            return null
        }

        return try {
            sensorManager.getSensorList(Sensor.TYPE_ALL)
                .find { sensor -> type == sensor.stringType }
                .also { sensor ->
                    if (sensor != null) {
                        Log.d(TAG, "Found sensor: ${sensor.name} for type: $type")
                    } else {
                        Log.d(TAG, "No sensor found for type: $type")
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting sensor list for type: $type", e)
            null
        }
    }

    /**
     * Get all sensors of a specific type.
     *
     * @param sensorManager the sensor manager to query
     * @param sensorType the sensor type (e.g., Sensor.TYPE_ACCELEROMETER)
     * @return list of sensors of the specified type
     */
    fun getSensorsByType(sensorManager: SensorManager?, sensorType: Int): List<Sensor> {
        if (sensorManager == null) {
            Log.w(TAG, "SensorManager is null")
            return emptyList()
        }

        return try {
            sensorManager.getSensorList(sensorType)
                .also { sensors ->
                    Log.d(TAG, "Found ${sensors.size} sensors of type: $sensorType")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting sensors of type: $sensorType", e)
            emptyList()
        }
    }

    /**
     * Check if a specific sensor type is available on the device.
     *
     * @param sensorManager the sensor manager to query
     * @param sensorType the sensor type to check
     * @return true if the sensor type is available
     */
    fun isSensorAvailable(sensorManager: SensorManager?, sensorType: Int): Boolean {
        return getSensorsByType(sensorManager, sensorType).isNotEmpty()
    }

    /**
     * Get the default sensor for a specific type.
     *
     * @param sensorManager the sensor manager to query
     * @param sensorType the sensor type
     * @return the default sensor, or null if not available
     */
    fun getDefaultSensor(sensorManager: SensorManager?, sensorType: Int): Sensor? {
        if (sensorManager == null) {
            Log.w(TAG, "SensorManager is null")
            return null
        }

        return try {
            sensorManager.getDefaultSensor(sensorType)
                .also { sensor ->
                    if (sensor != null) {
                        Log.d(TAG, "Default sensor found: ${sensor.name} for type: $sensorType")
                    } else {
                        Log.d(TAG, "No default sensor for type: $sensorType")
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting default sensor for type: $sensorType", e)
            null
        }
    }

    /**
     * Get all available sensors on the device.
     *
     * @param sensorManager the sensor manager to query
     * @return list of all available sensors
     */
    fun getAllSensors(sensorManager: SensorManager?): List<Sensor> {
        if (sensorManager == null) {
            Log.w(TAG, "SensorManager is null")
            return emptyList()
        }

        return try {
            sensorManager.getSensorList(Sensor.TYPE_ALL)
                .also { sensors ->
                    Log.d(TAG, "Total sensors available: ${sensors.size}")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting all sensors", e)
            emptyList()
        }
    }
}