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

class ThermalTileService : TileService() {
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var notificationManager: NotificationManager
    private var performanceNotification: Notification? = null
    private var batterySaverObserver: ContentObserver? = null
    @Volatile private var currentMode: Int = MODE_DEFAULT
    private val tileExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingLock = Any()
    @Volatile private var pendingMode: Int = MODE_PENDING_NONE
    private val applyRunnable = Runnable { flushPendingMode() }
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == THERMAL_ENABLED_KEY) {
            mainHandler.post { handleThermalProfilesChanged() }
        }
    }

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
        sharedPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        setupNotificationChannel()
        registerBatterySaverObserver()
    }

    override fun onStartListening() {
        super.onStartListening()
        logDebug("Tile start listening")
        handleThermalProfilesChanged()
    }

    override fun onClick() {
        super.onClick()
        if (isThermalProfilesEnabled()) {
            logDebug("Tile click ignored - thermal feature disabled")
            sharedPrefs.edit().putBoolean(THERMAL_ENABLED_KEY, false).apply()
            return
        }
        toggleThermalMode()
    }

    private fun toggleThermalMode() {
        val nextMode = when (currentMode) {
            MODE_UNKNOWN -> MODE_DEFAULT
            else -> (currentMode + 1) % 3
        }

        logDebug("Tile tapped - switching to ${modeLabel(nextMode)}")
        currentMode = nextMode
        updateTile()
        scheduleModeApply(nextMode)
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

    private fun scheduleModeApply(mode: Int, immediate: Boolean = false) {
        if (isThermalProfilesEnabled()) {
            logDebug("Skipping apply for ${modeLabel(mode)} because thermal profiles are enabled")
            return
        }
        synchronized(pendingLock) {
            pendingMode = mode
            mainHandler.removeCallbacks(applyRunnable)
            val delay = if (immediate) 0L else MODE_WRITE_DEBOUNCE_MS
            logDebug("Scheduled apply for ${modeLabel(mode)} in ${delay}ms")
            mainHandler.postDelayed(applyRunnable, delay)
        }
    }

    private fun flushPendingMode() {
        val modeToApply = synchronized(pendingLock) {
            val scheduled = pendingMode
            pendingMode = MODE_PENDING_NONE
            scheduled
        }

        if (modeToApply == MODE_PENDING_NONE) {
            logDebug("No pending thermal mode to apply")
            return
        }

        tileExecutor.execute {
            applyThermalMode(modeToApply)
        }
    }

    private fun applyThermalMode(requestedMode: Int) {


        val nodeValue = when (requestedMode) {
            MODE_BATTERY_SAVER -> THERMAL_VALUE_BATTERY_SAVER
            MODE_PERFORMANCE -> THERMAL_VALUE_PERFORMANCE
            else -> THERMAL_VALUE_DEFAULT
        }



        logDebug("Applying thermal mode ${modeLabel(requestedMode)} ($nodeValue)")
        val success = FileUtils.writeLine(THERMAL_SCONFIG, nodeValue.toString())
        Log.d(TAG, "Requested thermal mode ${modeLabel(requestedMode)} write success=$success")

        if (success) {
            setPerformanceModeActive(requestedMode)
            handlePerformanceNotification(requestedMode)
        }

        mainHandler.post {


            if (success) {
                currentMode = requestedMode
                logDebug("Thermal mode ${modeLabel(currentMode)} applied, refreshing tile")
            } else {
                val fallback = getCurrentThermalMode()
                if (fallback != MODE_UNKNOWN) {
                    currentMode = fallback
                    logDebug("Thermal write failed, fallback to ${modeLabel(currentMode)}")
                } else {
                    logDebug("Thermal write failed and kernel returned unknown state")
                }
            }
            updateTile()
        }
    }

    private fun handlePerformanceNotification(mode: Int) {
        if (mode == MODE_PERFORMANCE) {
            showPerformanceNotification()
        } else {
            cancelPerformanceNotification()
        }
    }

    private fun isThermalProfilesEnabled(): Boolean =
        sharedPrefs.getBoolean(THERMAL_ENABLED_KEY, true)

    private fun cancelPendingModeApply() {
        synchronized(pendingLock) {
            pendingMode = MODE_PENDING_NONE
            mainHandler.removeCallbacks(applyRunnable)
        }
    }

    private fun handleThermalProfilesChanged() {
        val enabled = isThermalProfilesEnabled()
        logDebug("Thermal profiles changed, enabled=$enabled")
        if (enabled) {
            cancelPendingModeApply()
            setPerformanceModeActive(MODE_DEFAULT)
            handlePerformanceNotification(MODE_DEFAULT)
            currentMode = MODE_DEFAULT
            updateTile()
            return
        }

        val detectedMode = getCurrentThermalMode()
        currentMode = if (detectedMode == MODE_UNKNOWN) {
            logDebug("Thermal profiles disabled, resetting thermal mode to default")
            scheduleModeApply(MODE_DEFAULT, immediate = true)
            MODE_DEFAULT
        } else {
            detectedMode
        }
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val (state, iconRes) = when (currentMode) {
            MODE_PERFORMANCE -> Tile.STATE_ACTIVE to R.drawable.ic_thermal_performance
            MODE_BATTERY_SAVER -> Tile.STATE_ACTIVE to R.drawable.ic_thermal_battery_saver
            else -> Tile.STATE_INACTIVE to R.drawable.ic_thermal_default
        }

        logDebug("Updating tile visuals to ${modeLabel(currentMode)} with state=$state")
        tile.state = state
        tile.icon = Icon.createWithResource(this, iconRes)
        tile.label = getString(R.string.thermal_tile_label)
        val subtitle = modeLabel(currentMode)
        tile.subtitle = subtitle
        tile.stateDescription = subtitle
        tile.updateTile()
    }

    private fun updateTileDisabled() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_ACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_thermal_default)
        tile.label = getString(R.string.thermal_tile_label)
        val subtitle = getString(R.string.thermal_tile_disabled_subtitle)
        tile.subtitle = subtitle
        tile.stateDescription = subtitle
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
        try {
            SystemProperties.set(SYS_PROP, mode.toString())
            logDebug("Performance mode active set to: $mode")
        } catch (e: SecurityException) {
            Log.w(TAG, "Unable to set $SYS_PROP", e)
        }
    }

    private fun registerBatterySaverObserver() {
        val observer = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                if (isThermalProfilesEnabled()) {
                    logDebug("Battery saver change ignored while thermal profiles enabled")
                    return
                }
                val isBatterySaverOn = Settings.Global.getInt(
                    contentResolver,
                    Settings.Global.LOW_POWER_MODE,
                    0
                ) == 1

                if (isBatterySaverOn && currentMode != MODE_BATTERY_SAVER) {
                    logDebug("System Battery Saver enabled, switching to Battery Saver thermal mode")
                    currentMode = MODE_BATTERY_SAVER
                    updateTile()
                    scheduleModeApply(MODE_BATTERY_SAVER)
                } else if (!isBatterySaverOn && currentMode == MODE_BATTERY_SAVER) {
                    logDebug("System Battery Saver disabled, restoring Default thermal mode")
                    currentMode = MODE_DEFAULT
                    updateTile()
                    scheduleModeApply(MODE_DEFAULT)
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
        mainHandler.removeCallbacks(applyRunnable)
        sharedPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
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
        private const val MODE_WRITE_DEBOUNCE_MS = 1000L

        private const val MODE_DEFAULT = 0
        private const val MODE_PERFORMANCE = 1
        private const val MODE_BATTERY_SAVER = 2
        private const val MODE_UNKNOWN = 3
        private const val MODE_PENDING_NONE = -1

        private const val THERMAL_VALUE_DEFAULT = 0
        private const val THERMAL_VALUE_PERFORMANCE = 9
        private const val THERMAL_VALUE_BATTERY_SAVER = 52
    }
}
