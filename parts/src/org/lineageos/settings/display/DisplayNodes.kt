/*
 * Copyright (C) 2022 The CipherOS Project
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

package org.lineageos.settings.display

/**
 * Constants and paths for display-related system nodes and preferences.
 * Provides centralized access to display control paths used across the device settings.
 */
object DisplayNodes {

    // DC Dimming settings
    const val DC_DIMMING_ENABLE_KEY = "dc_dimming_enable"
    const val DC_DIMMING_NODE = "/sys/devices/platform/soc/soc:qcom,dsi-display-primary/msm_fb_ea_enable"

    // High Brightness Mode settings
    const val HBM_ENABLE_KEY = "hbm_mode"
    const val HBM_NODE = "/sys/devices/platform/soc/soc:qcom,dsi-display-primary/hbm"

    // Backlight control
    const val BACKLIGHT = "/sys/class/backlight/panel0-backlight/brightness"

    // Legacy getter methods for backward compatibility
    @JvmStatic
    fun getDcDimmingEnableKey(): String = DC_DIMMING_ENABLE_KEY

    @JvmStatic
    fun getDcDimmingNode(): String = DC_DIMMING_NODE

    @JvmStatic
    fun getHbmEnableKey(): String = HBM_ENABLE_KEY

    @JvmStatic
    fun getHbmNode(): String = HBM_NODE

    @JvmStatic
    fun getBacklight(): String = BACKLIGHT
}