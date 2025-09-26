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

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
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

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applicationsState = ApplicationsState.getInstance(requireActivity().application)
        session = applicationsState.newSession(this)
        session.onResume()
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
    }

    override fun onPause() {
        super.onPause()
        session.onPause()
    }

    // ApplicationsState.Callbacks implementation
    override fun onRunningStateChanged(running: Boolean) {}
    override fun onPackageListChanged() = rebuild()
    override fun onRebuildComplete(entries: ArrayList<ApplicationsState.AppEntry>) = handleAppEntries(entries)
    override fun onPackageIconChanged() {}
    override fun onPackageSizeChanged(packageName: String) {}
    override fun onAllSizesComputed() {}
    override fun onLauncherInfoChanged() {}
    override fun onLoadEntriesCompleted() {}

    private fun handleAppEntries(entries: List<ApplicationsState.AppEntry>) {
        val sections = mutableListOf<String>()
        val positions = mutableListOf<Int>()
        val pm = requireActivity().packageManager
        var lastSectionIndex: String? = null
        var offset = 0

        entries.forEach { entry ->
            val info = entry.info
            val label = info.loadLabel(pm).toString()
            val sectionIndex = when {
                !info.enabled -> "--"
                TextUtils.isEmpty(label) -> ""
                else -> label.substring(0, 1).uppercase()
            }

            if (lastSectionIndex == null || !TextUtils.equals(sectionIndex, lastSectionIndex)) {
                sections.add(sectionIndex)
                positions.add(offset)
                lastSectionIndex = sectionIndex
            }
            offset++
        }

        allPackagesAdapter.setEntries(entries, sections, positions)
        entryMap.clear()
        entries.forEach { entry ->
            entryMap[entry.info.packageName] = entry
        }
    }

    private fun rebuild() {
        session.rebuild(activityFilter, ApplicationsState.ALPHA_COMPARATOR)
    }

    private fun getStateDrawable(state: Int): Int = when (state) {
        ThermalUtils.STATE_BENCHMARK -> R.drawable.ic_thermal_benchmark
        ThermalUtils.STATE_BROWSER -> R.drawable.ic_thermal_browser
        ThermalUtils.STATE_CAMERA -> R.drawable.ic_thermal_camera
        ThermalUtils.STATE_DIALER -> R.drawable.ic_thermal_dialer
        ThermalUtils.STATE_GAMING -> R.drawable.ic_thermal_gaming
        ThermalUtils.STATE_STREAMING -> R.drawable.ic_thermal_streaming
        else -> R.drawable.ic_thermal_default
    }

    private inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.app_name)
        val mode: Spinner = view.findViewById(R.id.app_mode)
        val icon: ImageView = view.findViewById(R.id.app_icon)
        val stateIcon: ImageView = view.findViewById(R.id.state)
        val touchIcon: ImageView = view.findViewById(R.id.touch)

        init {
            view.tag = this
        }
    }

    private inner class ModeAdapter(context: Context) : BaseAdapter() {
        private val inflater = LayoutInflater.from(context)
        private val items = intArrayOf(
            R.string.thermal_default,
            R.string.thermal_benchmark,
            R.string.thermal_browser,
            R.string.thermal_camera,
            R.string.thermal_dialer,
            R.string.thermal_gaming,
            R.string.thermal_streaming
        )

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = 0L

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView as? TextView ?: inflater.inflate(
                android.R.layout.simple_spinner_dropdown_item, parent, false
            ) as TextView

            view.apply {
                setText(items[position])
                textSize = 14f
            }
            return view
        }
    }

    private inner class AllPackagesAdapter(context: Context) : 
        RecyclerView.Adapter<ViewHolder>(), AdapterView.OnItemSelectedListener, SectionIndexer {
        
        var entries = listOf<ApplicationsState.AppEntry>()
        private var sections = emptyArray<String>()
        private var positions = intArrayOf()

        init {
            activityFilter = ActivityFilter(context.packageManager)
        }

        override fun getItemCount() = entries.size
        override fun getItemId(position: Int) = entries[position].id

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val holder = ViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.thermal_list_item, parent, false)
            )
            holder.mode.apply {
                adapter = ModeAdapter(holder.itemView.context)
                onItemSelectedListener = this@AllPackagesAdapter
            }
            return holder
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = entries[position]

            holder.touchIcon.setOnClickListener {
                val touchSettingsFragment = TouchSettingsFragment().apply {
                    arguments = Bundle().apply {
                        putString("appName", entry.label)
                        putString("packageName", entry.info.packageName)
                    }
                }
                (activity as? androidx.fragment.app.FragmentActivity)?.supportFragmentManager?.beginTransaction()
                    ?.replace(R.id.content_frame, touchSettingsFragment, "touchSettingsFragment")
                    ?.addToBackStack(null)
                    ?.commit()
            }

            holder.title.apply {
                text = entry.label
                setOnClickListener { holder.mode.performClick() }
            }

            applicationsState.ensureIcon(entry)
            holder.icon.setImageDrawable(entry.icon)

            val packageState = thermalUtils.getStateForPackage(entry.info.packageName)
            holder.mode.apply {
                setSelection(packageState, false)
                tag = entry
            }

            val stateIconDrawable = getStateDrawable(packageState)
            holder.touchIcon.visibility = if (stateIconDrawable == R.drawable.ic_thermal_gaming || 
                                             stateIconDrawable == R.drawable.ic_thermal_benchmark) {
                View.VISIBLE
            } else {
                View.GONE
            }
            holder.stateIcon.setImageResource(stateIconDrawable)
        }

        fun setEntries(entries: List<ApplicationsState.AppEntry>, sections: List<String>, positions: List<Int>) {
            this.entries = entries
            this.sections = sections.toTypedArray()
            this.positions = positions.toIntArray()
            notifyDataSetChanged()
        }

        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            val entry = parent?.tag as? ApplicationsState.AppEntry ?: return
            val currentState = thermalUtils.getStateForPackage(entry.info.packageName)
            if (currentState != position) {
                thermalUtils.writePackage(entry.info.packageName, position)
                notifyDataSetChanged()
            }
        }

        override fun onNothingSelected(parent: AdapterView<*>?) {}

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
        private val launcherResolveInfoList = mutableListOf<String>()

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

        override fun init() {}

        override fun filterApp(entry: ApplicationsState.AppEntry): Boolean {
            var show = !allPackagesAdapter.entries.any { it.info.packageName == entry.info.packageName }
            if (show) {
                synchronized(launcherResolveInfoList) {
                    show = launcherResolveInfoList.contains(entry.info.packageName)
                }
            }
            return show
        }
    }
}