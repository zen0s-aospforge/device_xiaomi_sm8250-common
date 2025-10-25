/*
 * Copyright (C) 2023-2024 Paranoid Android
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.settings.display;

import android.os.Bundle;
import org.lineageos.settings.R;
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

public class ColorModeActivity extends CollapsingToolbarBaseActivity {

    private static final String TAG_COLORMODE = "colormode";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getFragmentManager().beginTransaction().replace(R.id.content_frame,
                new ColorModeFragment(), TAG_COLORMODE).commit();
    }
}