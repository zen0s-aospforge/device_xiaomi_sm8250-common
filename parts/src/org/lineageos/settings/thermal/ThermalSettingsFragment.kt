/**
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

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SectionIndexer
import android.widget.TextView
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.settingslib.applications.ApplicationsState
import org.lineageos.settings.R
import java.util.*

class ThermalSettingsFragment : PreferenceFragmentCompat(), ApplicationsState.Callbacks {

    private lateinit var allPackagesAdapter: AllPackagesAdapter
    private lateinit var applicationsState: ApplicationsState
    private lateinit var session: ApplicationsState.Session
    private lateinit var activityFilter: ActivityFilter
    private val entryMap = mutableMapOf<String, ApplicationsState.AppEntry>()
    private lateinit var appsRecyclerView: RecyclerView
    private lateinit var thermalUtils: ThermalUtils

    // Map of thermal states to their string resource IDs
    private val thermalModeStringResMap = mapOf(
        ThermalUtils.STATE_DEFAULT to R.string.thermal_default,
        ThermalUtils.STATE_ULTRACOOL to R.string.thermal_ultracool,
        ThermalUtils.STATE_STREAMING to R.string.thermal_streaming,
        ThermalUtils.STATE_BROWSER to R.string.thermal_browser,
        ThermalUtils.STATE_CAMERA to R.string.thermal_camera,
        ThermalUtils.STATE_DIALER to R.string.thermal_dialer,
        ThermalUtils.STATE_GAMING to R.string.thermal_gaming,
        ThermalUtils.STATE_BENCHMARK to R.string.thermal_benchmark
    )

    // Options for the AlertDialog (text and state value)
    private val thermalModeDialogOptions by lazy {
        listOf(
            Pair(getString(R.string.thermal_default), ThermalUtils.STATE_DEFAULT),
            Pair(getString(R.string.thermal_ultracool), ThermalUtils.STATE_ULTRACOOL),
            Pair(getString(R.string.thermal_streaming), ThermalUtils.STATE_STREAMING),
            Pair(getString(R.string.thermal_browser), ThermalUtils.STATE_BROWSER),
            Pair(getString(R.string.thermal_camera), ThermalUtils.STATE_CAMERA),
            Pair(getString(R.string.thermal_dialer), ThermalUtils.STATE_DIALER),
            Pair(getString(R.string.thermal_gaming), ThermalUtils.STATE_GAMING),
            Pair(getString(R.string.thermal_benchmark), ThermalUtils.STATE_BENCHMARK)
        )
    }

    // **NEW**: Map of thermal states to their icon resource IDs
    private val thermalModeIconResMap = mapOf(
        ThermalUtils.STATE_DEFAULT to R.drawable.ic_thermal_default,
        ThermalUtils.STATE_ULTRACOOL to R.drawable.ic_thermal_ultracool,
        ThermalUtils.STATE_STREAMING to R.drawable.ic_thermal_streaming,
        ThermalUtils.STATE_BROWSER to R.drawable.ic_thermal_browser,
        ThermalUtils.STATE_CAMERA to R.drawable.ic_thermal_camera,
        ThermalUtils.STATE_DIALER to R.drawable.ic_thermal_dialer,
        ThermalUtils.STATE_GAMING to R.drawable.ic_thermal_gaming,
        ThermalUtils.STATE_BENCHMARK to R.drawable.ic_thermal_benchmark
        )

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applicationsState = ApplicationsState.getInstance(requireActivity().application)
        session = applicationsState.newSession(this)
        activityFilter = ActivityFilter(requireActivity().packageManager)

        allPackagesAdapter = AllPackagesAdapter(requireActivity())
        thermalUtils = ThermalUtils(requireActivity())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.thermal_layout, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        appsRecyclerView = view.findViewById<RecyclerView>(R.id.thermal_rv_view).apply {
            layoutManager = LinearLayoutManager(requireActivity())
            adapter = allPackagesAdapter
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        session.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        session.onResume()
        rebuild()
        allPackagesAdapter.notifyDataSetChanged()
    }

    override fun onPause() {
        super.onPause()
        session.onPause()
    }

    override fun onRunningStateChanged(running: Boolean) {}
    override fun onPackageListChanged() = rebuild()
    override fun onRebuildComplete(entries: ArrayList<ApplicationsState.AppEntry>) = handleAppEntries(entries)
    override fun onPackageIconChanged() {
        // Ensure UI updates when icons are loaded/changed
        allPackagesAdapter.notifyDataSetChanged()
    } 
    override fun onPackageSizeChanged(packageName: String) {}
    override fun onAllSizesComputed() {}
    override fun onLauncherInfoChanged() {}
    override fun onLoadEntriesCompleted() {
        // First-time app list load completes here; trigger rebuild to apply filters
        rebuild()
    }

    private fun handleAppEntries(entries: List<ApplicationsState.AppEntry>) {
        val sections = mutableListOf<String>()
        val positions = mutableListOf<Int>()
        val pm = requireActivity().packageManager
        var lastSectionIndex: String? = null
        var offset = 0

        val filteredEntries = entries.filter { activityFilter.filterApp(it) }

        filteredEntries.forEach { entry ->
            val info = entry.info
            val label = entry.label ?: info.loadLabel(pm).toString()
            val sectionIndex = when {
                !info.enabled -> "--"
                TextUtils.isEmpty(label) -> ""
                else -> label.substring(0, 1).uppercase(Locale.getDefault())
            }

            if (lastSectionIndex == null || !TextUtils.equals(sectionIndex, lastSectionIndex)) {
                sections.add(sectionIndex)
                positions.add(offset)
                lastSectionIndex = sectionIndex
            }
            offset++
        }

        allPackagesAdapter.setEntries(filteredEntries, sections, positions)
        entryMap.clear()
        filteredEntries.forEach { entry ->
            entryMap[entry.info.packageName] = entry
        }
    }

    private fun rebuild() {
        session.rebuild(activityFilter, ApplicationsState.ALPHA_COMPARATOR)
    }

    // This function can remain if needed elsewhere, but not directly used for list item summary now
    private fun getThermalModeString(modeState: Int): String {
        return thermalModeStringResMap[modeState]?.let { getString(it) } ?: getString(R.string.thermal_default)
    }
    
    // ViewHolder updated for an icon instead of text summary
    private inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.app_name)
        // Show text summary if available in layout
        val thermalModeText: TextView? = view.findViewById(R.id.app_thermal_summary)
        val thermalModeIcon: ImageView? = view.findViewById(R.id.app_thermal_icon)
        val icon: ImageView = view.findViewById(R.id.app_icon) // App's main icon
    }

    private inner class AllPackagesAdapter(private val context: Context) :
        RecyclerView.Adapter<ViewHolder>(), SectionIndexer {
        
        var entries = listOf<ApplicationsState.AppEntry>()
        private var sections = emptyArray<String>()
        private var positions = intArrayOf()

        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.thermal_list_item, parent, false)
            )
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = entries[position]

            holder.title.text = entry.label
            applicationsState.ensureIcon(entry)
            holder.icon.setImageDrawable(entry.icon)

            val currentModeState = thermalUtils.getStateForPackage(entry.info.packageName)

            // Set the thermal mode TEXT if present
            holder.thermalModeText?.let { summaryView ->
                summaryView.text = getThermalModeString(currentModeState)
                summaryView.visibility = View.VISIBLE
            }

            // Also set the thermal mode ICON if present
            holder.thermalModeIcon?.let { iconView ->
                val modeIconRes = thermalModeIconResMap[currentModeState]
                if (modeIconRes != null) {
                    iconView.setImageResource(modeIconRes)
                    iconView.visibility = View.VISIBLE
                } else {
                    iconView.setImageResource(R.drawable.ic_thermal_default)
                    iconView.visibility = View.VISIBLE
                }
            }

            holder.itemView.setOnClickListener {
                val packageName = entry.info.packageName
                val appLabel = entry.label ?: entry.info.loadLabel(context.packageManager).toString()

                if (packageName == null) {
                    Log.e("ThermalSettings", "PackageName is null for ${entry.label}, cannot show dialog.")
                    return@setOnClickListener
                }

                val modeNames = thermalModeDialogOptions.map { it.first }.toTypedArray()
                // Need to re-fetch currentModeState here as it might be from a previous bind if view is recycled
                val currentDialogModeState = thermalUtils.getStateForPackage(packageName) 
                val currentModeIndex = thermalModeDialogOptions.indexOfFirst { it.second == currentDialogModeState }
                var selectedModeIndex = currentModeIndex 

                AlertDialog.Builder(context)
                    .setTitle(getString(R.string.dialog_title_select_thermal_profile, appLabel))
                    .setSingleChoiceItems(modeNames, currentModeIndex) { _, which ->
                        selectedModeIndex = which
                    }
                    .setPositiveButton(android.R.string.ok) { dialog, _ ->
                        if (selectedModeIndex != -1 && selectedModeIndex < thermalModeDialogOptions.size) {
                            val (_, selectedModeValue) = thermalModeDialogOptions[selectedModeIndex]
                            val previousModeState = thermalUtils.getStateForPackage(packageName)
                            if (previousModeState != selectedModeValue) {
                                thermalUtils.writePackage(packageName, selectedModeValue)
                                notifyItemChanged(holder.adapterPosition) // Crucial to update the icon
                            }
                        }
                        dialog.dismiss()
                    }
                    .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                        dialog.dismiss()
                    }
                    .show()
            }
        }

        fun setEntries(entries: List<ApplicationsState.AppEntry>, sections: List<String>, positions: List<Int>) {
            this.entries = entries
            this.sections = sections.toTypedArray()
            this.positions = positions.toIntArray()
            notifyDataSetChanged()
        }

        override fun getPositionForSection(section: Int): Int {
            if (section < 0 || section >= sections.size) return -1
            return positions[section]
        }

        override fun getSectionForPosition(position: Int): Int {
            if (position < 0 || position >= itemCount) return -1
            val index = Arrays.binarySearch(positions, position)
            return if (index >= 0) index else -index - 2
        }

        override fun getSections(): Array<Any> = sections as Array<Any>
    }

    private inner class ActivityFilter(private val packageManager: PackageManager) : ApplicationsState.AppFilter {
        private val launcherResolveInfoList = Collections.synchronizedList(mutableListOf<String>())

        init {
            updateLauncherInfoList()
        }

        fun updateLauncherInfoList() {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolveInfoList = packageManager.queryIntentActivities(intent, 0)
            synchronized(launcherResolveInfoList) {
                launcherResolveInfoList.clear()
                resolveInfoList.forEach { resolveInfo ->
                    launcherResolveInfoList.add(resolveInfo.activityInfo.packageName)
                }
            }
        }

        override fun init() {
            updateLauncherInfoList()
        }
        
        override fun filterApp(entry: ApplicationsState.AppEntry): Boolean {
            synchronized(launcherResolveInfoList) {
                return launcherResolveInfoList.contains(entry.info.packageName)
            }
        }
    }
}
