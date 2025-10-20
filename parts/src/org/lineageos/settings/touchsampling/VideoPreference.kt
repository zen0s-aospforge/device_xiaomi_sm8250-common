/*
 * Copyright (C) 2025 The LineageOS Project
 * Copyright (C) 2025 kenway214
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

package org.lineageos.settings.touchsampling

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import android.widget.VideoView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import org.lineageos.settings.R

class VideoPreference(context: Context, attrs: AttributeSet) : Preference(context, attrs) {

    private var videoView: VideoView? = null
    private var isVideoPrepared = false

    init {
        layoutResource = R.layout.htsr_media_layout
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        videoView = holder.findViewById(R.id.htsr_video) as VideoView?
        videoView?.let { video ->
            val videoUri = Uri.parse("android.resource://${context.packageName}/${R.raw.htsr_demo}")
            video.setVideoURI(videoUri)

            video.setOnPreparedListener {
                isVideoPrepared = true
                video.start()
            }

            video.setOnCompletionListener { video.start() }
        }
    }

    fun restartVideo() {
        videoView?.takeIf { isVideoPrepared }?.let { video ->
            video.seekTo(0)
            video.start()
        }
    }
}