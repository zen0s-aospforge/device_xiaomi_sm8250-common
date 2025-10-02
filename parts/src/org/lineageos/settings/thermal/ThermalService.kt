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
        private const val DEBUG = false
        private const val SETTINGS_GAME_LIST = "gamespace_game_list"
    }

    private var previousApp: String = ""
    private lateinit var thermalUtils: ThermalUtils
    private var activityTaskManager: IActivityTaskManager? = null

    private val intentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            previousApp = ""
            thermalUtils.setDefaultThermalProfile()
            thermalUtils.resetTouchModes()
        }
    }

    override fun onCreate() {
        if (DEBUG) Log.d(TAG, "Creating service")
        
        try {
            activityTaskManager = ActivityTaskManager.getService()
            activityTaskManager?.registerTaskStackListener(taskListener)
        } catch (e: RemoteException) {
            // Do nothing
        }
        
        thermalUtils = ThermalUtils(this)
        registerReceiver()
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (DEBUG) Log.d(TAG, "Starting service")
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
            try {
                val info = activityTaskManager?.focusedRootTaskInfo
                val topActivity = info?.topActivity ?: return

                val foregroundApp = topActivity.packageName
                if (foregroundApp != previousApp) {
                    if (!isConfigured(foregroundApp) && isListedOnGameSpace(foregroundApp)) {
                        thermalUtils.setThermalProfileForce(ThermalUtils.STATE_GAMING)
                    } else {
                        thermalUtils.setThermalProfile(foregroundApp)
                    }
                    previousApp = foregroundApp
                }
            } catch (e: Exception) {
                // Catch all exceptions to prevent service crashes
            }
        }
    }
}