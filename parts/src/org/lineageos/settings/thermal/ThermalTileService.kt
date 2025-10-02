package org.lineageos.settings.thermal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.ContentObserver
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemProperties
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.preference.PreferenceManager
import org.lineageos.settings.R
import org.lineageos.settings.utils.FileUtils
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

class ThermalTileService : TileService() {
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var notificationManager: NotificationManager
    private var performanceNotification: Notification? = null
    private var batterySaverObserver: ContentObserver? = null
    @Volatile private var currentMode: Int = MODE_DEFAULT
    private val tileExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val updateInProgress = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun logDebug(message: String) {
        if (DEBUG) Log.d(TAG, message)
    }

    override fun onCreate() {
        super.onCreate()
        sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (!sharedPrefs.contains(THERMAL_ENABLED_KEY)) {
            sharedPrefs.edit().putBoolean(THERMAL_ENABLED_KEY, true).apply()
        }

        setupNotificationChannel()
        registerBatterySaverObserver()
    }

    override fun onStartListening() {
        super.onStartListening()
        logDebug("Tile start listening")
        if (sharedPrefs.getBoolean(THERMAL_ENABLED_KEY, true)) {
            logDebug("Thermal feature disabled via prefs - tile unavailable")
            updateTileDisabled()
            return
        }

        currentMode = getCurrentThermalMode()
        if (currentMode == MODE_UNKNOWN) {
            currentMode = MODE_DEFAULT
            setThermalMode(currentMode)
        }
        logDebug("Tile listening with mode: ${modeLabel(currentMode)}")
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (sharedPrefs.getBoolean(THERMAL_ENABLED_KEY, true)) {
            logDebug("Tile click ignored - thermal feature disabled")
            return
        }
        toggleThermalMode()
    }

    private fun toggleThermalMode() {
        if (!updateInProgress.compareAndSet(false, true)) {
            logDebug("Thermal mode change already in progress, ignoring tap")
            return
        }

        val nextMode = when (currentMode) {
            MODE_UNKNOWN -> MODE_DEFAULT
            else -> (currentMode + 1) % 3
        }

        logDebug("Tile tapped - switching to ${modeLabel(nextMode)}")
        currentMode = nextMode
        updateTile()

        tileExecutor.execute {
            try {
                setThermalMode(nextMode)
                val refreshedMode = getCurrentThermalMode()
                if (refreshedMode != MODE_UNKNOWN) {
                    logDebug("Thermal node now reports ${modeLabel(refreshedMode)}")
                    currentMode = refreshedMode
                }
            } finally {
                updateInProgress.set(false)
                mainHandler.post {
                    logDebug("Tile refresh after background write - ${modeLabel(currentMode)}")
                    updateTile()
                }
            }
        }
    }

    private fun getCurrentThermalMode(): Int {
        val line = FileUtils.readOneLine(THERMAL_SCONFIG) ?: return MODE_UNKNOWN
        val value = line.trim().toIntOrNull() ?: return MODE_UNKNOWN
        return when (value) {
            0 -> MODE_DEFAULT
            9 -> MODE_PERFORMANCE
            52 -> MODE_BATTERY_SAVER
            else -> MODE_UNKNOWN
        }
    }

    private fun setThermalMode(mode: Int) {
        val thermalValue = when (mode) {
            MODE_PERFORMANCE -> 9
            MODE_BATTERY_SAVER -> 52
            MODE_DEFAULT, MODE_UNKNOWN -> 0
            else -> 0
        }

        val success = FileUtils.writeLine(THERMAL_SCONFIG, thermalValue.toString())
        logDebug("Requested thermal mode ${modeLabel(mode)} (${thermalValue}) success=$success")

        when (mode) {
            MODE_PERFORMANCE -> {
                setPerformanceModeActive(2)
                enableBatterySaver(false)
                showPerformanceNotification()
            }
            MODE_BATTERY_SAVER -> {
                setPerformanceModeActive(0)
                enableBatterySaver(true)
                cancelPerformanceNotification()
            }
            else -> {
                setPerformanceModeActive(1)
                enableBatterySaver(false)
                cancelPerformanceNotification()
            }
        }
    }

    private fun enableBatterySaver(enable: Boolean) {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val isBatterySaverEnabled = powerManager.isPowerSaveMode
        if (enable && !isBatterySaverEnabled) {
            powerManager.setPowerSaveModeEnabled(true)
            logDebug("Battery Saver mode enabled")
        } else if (!enable && isBatterySaverEnabled) {
            powerManager.setPowerSaveModeEnabled(false)
            logDebug("Battery Saver mode disabled")
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        logDebug("Updating tile visuals to ${modeLabel(currentMode)}")
        when (currentMode) {
            MODE_PERFORMANCE -> {
                tile.state = Tile.STATE_ACTIVE
                tile.icon = Icon.createWithResource(this, R.drawable.ic_thermal_performance)
            }
            MODE_BATTERY_SAVER -> {
                tile.state = Tile.STATE_INACTIVE
                tile.icon = Icon.createWithResource(this, R.drawable.ic_thermal_battery_saver)
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.icon = Icon.createWithResource(this, R.drawable.ic_thermal_default)
            }
        }
        tile.label = getString(R.string.thermal_tile_label)
        tile.subtitle = modeLabel(currentMode)
        tile.updateTile()
    }

    private fun updateTileDisabled() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_UNAVAILABLE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_thermal_default)
        tile.label = getString(R.string.thermal_tile_label)
        tile.subtitle = getString(R.string.thermal_tile_disabled_subtitle)
        tile.updateTile()
    }

    private fun setupNotificationChannel() {
        val channel = NotificationChannel(
            TAG,
            getString(R.string.perf_mode_title),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        channel.setBlockable(true)
        notificationManager.createNotificationChannel(channel)
    }

    private fun showPerformanceNotification() {
        val intent = Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, TAG)
            .setContentTitle(getString(R.string.perf_mode_title))
            .setContentText(getString(R.string.perf_mode_notification))
            .setSmallIcon(R.drawable.ic_thermal_performance)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build().apply {
                flags = flags or Notification.FLAG_NO_CLEAR
            }
        performanceNotification = notification
        notificationManager.notify(NOTIFICATION_ID_PERFORMANCE, notification)
    }

    private fun cancelPerformanceNotification() {
        performanceNotification = null
        notificationManager.cancel(NOTIFICATION_ID_PERFORMANCE)
    }

    private fun setPerformanceModeActive(mode: Int) {
        SystemProperties.set(SYS_PROP, mode.toString())
        logDebug("Performance mode active set to: $mode")
    }

    private fun registerBatterySaverObserver() {
        val observer = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                val isBatterySaverOn = Settings.Global.getInt(
                    contentResolver,
                    Settings.Global.LOW_POWER_MODE,
                    0
                ) == 1

                if (isBatterySaverOn && (currentMode == MODE_DEFAULT || currentMode == MODE_PERFORMANCE)) {
                    logDebug("Battery saver enabled, switching to battery saver thermal mode")
                    currentMode = MODE_BATTERY_SAVER
                    setThermalMode(currentMode)
                    updateTile()
                }
            }
        }

        batterySaverObserver = observer

        contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.LOW_POWER_MODE),
            false,
            observer
        )
    }

    private fun modeLabel(mode: Int): String = when (mode) {
        MODE_PERFORMANCE -> getString(R.string.thermal_mode_performance)
        MODE_BATTERY_SAVER -> getString(R.string.thermal_mode_battery_saver)
        MODE_DEFAULT -> getString(R.string.thermal_mode_default)
        else -> getString(R.string.thermal_mode_unknown)
    }

    override fun onDestroy() {
        super.onDestroy()
        batterySaverObserver?.let { contentResolver.unregisterContentObserver(it) }
        cancelPerformanceNotification()
        tileExecutor.shutdownNow()
        logDebug("Tile service destroyed")
    }

    companion object {
        private const val TAG = "ThermalTileService"
        private const val DEBUG = true
        private const val THERMAL_SCONFIG = "/sys/class/thermal/thermal_message/sconfig"
        private const val THERMAL_ENABLED_KEY = "thermal_enabled"
        private const val SYS_PROP = "sys.perf_mode_active"
        private const val NOTIFICATION_ID_PERFORMANCE = 1001

        private const val MODE_DEFAULT = 0
        private const val MODE_PERFORMANCE = 1
        private const val MODE_BATTERY_SAVER = 2
        private const val MODE_UNKNOWN = 3
    }
}
