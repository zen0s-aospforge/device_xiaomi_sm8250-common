/*
 * Copyright (C) 2020 The LineageOS Project
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

package org.lineageos.settings.thermal

import android.app.ActivityTaskManager
import android.app.ActivityTaskManager.RootTaskInfo
import android.app.IActivityTaskManager
import android.app.Service
import android.app.TaskStackListener
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.IBinder
import android.os.RemoteException
import android.provider.Settings
import android.util.Log

class ThermalService : Service() {

    companion object {
        private const val TAG = "ThermalService"
        private const val DEBUG = true
        private const val SETTINGS_GAME_LIST = "gamespace_game_list"
    }

    private fun logDebug(message: String) {
        if (DEBUG) Log.d(TAG, message)
    }

    private var previousApp: String = ""
    private lateinit var thermalUtils: ThermalUtils
    private var activityTaskManager: IActivityTaskManager? = null
    private var isTaskListenerRegistered = false

    private val intentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    handleScreenOff()
                }
                Intent.ACTION_SCREEN_ON -> {
                    handleScreenOn()
                }
            }
        }
    }

    override fun onCreate() {
    logDebug("Creating service")
        
        activityTaskManager = ActivityTaskManager.getService()
        registerTaskListener()
        
        thermalUtils = ThermalUtils(this)
        registerReceiver()
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logDebug("Starting service")
        if (!thermalUtils.isEnabled()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        thermalUtils.updateTouchRotation()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(intentReceiver)
        unregisterTaskListener()
    }

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(intentReceiver, filter)
    }

    private fun isListedOnGameSpace(packageName: String?): Boolean {
        if (packageName == null) return false
        
        val gameListString = Settings.System.getString(contentResolver, SETTINGS_GAME_LIST)
            ?: return false
            
        val gameList = gameListString.split(";")
        if (gameList.isEmpty()) return false

        return gameList.asSequence()
            .map { data ->
                val userGame = data.split("=")
                if (userGame.size == 2) userGame[0] else data
            }
            .any { it == packageName }
    }

    private fun isConfigured(packageName: String): Boolean {
        return thermalUtils.getStateForPackage(packageName) != ThermalUtils.STATE_DEFAULT
    }

    private val taskListener = object : TaskStackListener() {
        override fun onTaskStackChanged() {
            applyThermalForForegroundApp()
        }
    }

    private fun applyThermalForForegroundApp() {
        try {
            val info: RootTaskInfo = activityTaskManager?.focusedRootTaskInfo ?: run {
                logDebug("No focused root task info available")
                return
            }
            val foregroundApp = info.topActivity?.packageName ?: run {
                logDebug("Focused task missing package name")
                return
            }
            logDebug("Foreground app detected: $foregroundApp")
            handleForegroundPackage(foregroundApp)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply thermal profile", e)
        }
    }

    private fun handleForegroundPackage(packageName: String) {
        if (packageName == previousApp) {
            logDebug("Package $packageName already handled, skipping")
            return
        }

        if (!isConfigured(packageName) && isListedOnGameSpace(packageName)) {
            logDebug("$packageName listed in GameSpace without profile, forcing gaming state")
            thermalUtils.setThermalProfileForce(ThermalUtils.STATE_GAMING)
        } else {
            logDebug("Applying configured thermal profile for $packageName")
            thermalUtils.setThermalProfile(packageName)
        }
        previousApp = packageName
    }

    private fun handleScreenOff() {
        logDebug("Screen off received - resetting thermal state and pausing monitoring")
        previousApp = ""
        thermalUtils.setDefaultThermalProfile()
        thermalUtils.resetTouchModes()
        unregisterTaskListener()
    }

    private fun handleScreenOn() {
        logDebug("Screen on received - resuming monitoring")
        previousApp = ""
        registerTaskListener()
        applyThermalForForegroundApp()
    }

    private fun registerTaskListener() {
        if (isTaskListenerRegistered) return
        try {
            activityTaskManager?.registerTaskStackListener(taskListener)
            isTaskListenerRegistered = true
            logDebug("Task stack listener registered")
        } catch (e: RemoteException) {
            Log.w(TAG, "Failed to register task stack listener", e)
        }
    }

    private fun unregisterTaskListener() {
        if (!isTaskListenerRegistered) return
        try {
            activityTaskManager?.unregisterTaskStackListener(taskListener)
        } catch (e: RemoteException) {
            Log.w(TAG, "Failed to unregister task stack listener", e)
        } finally {
            isTaskListenerRegistered = false
            logDebug("Task stack listener unregistered")
        }
    }
}