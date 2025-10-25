/*
 * Copyright (C) 2023-2024 Paranoid Android
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.display;

import android.content.Context;
import android.os.Bundle;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.Preference.OnPreferenceChangeListener;
import androidx.preference.PreferenceFragment;
import android.provider.Settings;

import org.lineageos.settings.R;

public class ColorModeFragment extends PreferenceFragment implements
        OnPreferenceChangeListener {

    private ListPreference mColorModePreference;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.color_mode_settings);

        mColorModePreference = (ListPreference) findPreference("color_mode");
        mColorModePreference.setOnPreferenceChangeListener(this);

        // Set current value
        int currentMode = Settings.System.getInt(getActivity().getContentResolver(),
                Settings.System.DISPLAY_COLOR_MODE, 257);
        mColorModePreference.setValue(String.valueOf(currentMode));
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mColorModePreference) {
            int colorMode = Integer.parseInt((String) newValue);
            Settings.System.putInt(getActivity().getContentResolver(),
                    Settings.System.DISPLAY_COLOR_MODE, colorMode);
            return true;
        }
        return false;
    }
}