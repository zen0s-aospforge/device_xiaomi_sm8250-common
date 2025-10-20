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

package org.lineageos.settings.refreshrate

import android.annotation.Nullable
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.SectionIndexer
import android.widget.Spinner
import android.widget.TextView
import androidx.preference.PreferenceFragment
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.android.settingslib.applications.ApplicationsState
import org.lineageos.settings.R
import java.util.ArrayList
import java.util.Arrays
import java.util.HashMap

class RefreshSettingsFragment : PreferenceFragment(),
    ApplicationsState.Callbacks {

    private lateinit var mAllPackagesAdapter: AllPackagesAdapter
    private lateinit var mApplicationsState: ApplicationsState
    private lateinit var mSession: ApplicationsState.Session
    private lateinit var mActivityFilter: ActivityFilter
    private val mEntryMap = HashMap<String, ApplicationsState.AppEntry>()
    private lateinit var mRefreshUtils: RefreshUtils
    private lateinit var mAppsRecyclerView: RecyclerView

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // No preferences needed
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mApplicationsState = ApplicationsState.getInstance(activity!!.application)
        mSession = mApplicationsState.newSession(this)
        mSession.onResume()
        mActivityFilter = ActivityFilter(activity!!.packageManager)

        mAllPackagesAdapter = AllPackagesAdapter(activity!!)
        mRefreshUtils = RefreshUtils(activity!!)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.refresh_layout, container, false)
    }

    override fun onViewCreated(view: View, @Nullable savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mAppsRecyclerView = view.findViewById(R.id.refresh_rv_view)
        mAppsRecyclerView.layoutManager = LinearLayoutManager(activity)
        mAppsRecyclerView.adapter = mAllPackagesAdapter
    }

    override fun onResume() {
        super.onResume()
        activity?.setTitle(resources.getString(R.string.refresh_title))
        rebuild()
    }

    override fun onDestroy() {
        super.onDestroy()
        mSession.onPause()
        mSession.onDestroy()
    }

    override fun onPackageListChanged() {
        mActivityFilter.updateLauncherInfoList()
        rebuild()
    }

    override fun onRebuildComplete(entries: ArrayList<ApplicationsState.AppEntry>?) {
        if (entries != null) {
            handleAppEntries(entries)
            mAllPackagesAdapter.notifyDataSetChanged()
        }
    }

    override fun onLoadEntriesCompleted() {
        rebuild()
    }

    override fun onAllSizesComputed() {
        // Not needed
    }

    override fun onLauncherInfoChanged() {
        // Not needed
    }

    override fun onPackageIconChanged() {
        // Not needed
    }

    override fun onPackageSizeChanged(packageName: String?) {
        // Not needed
    }

    override fun onRunningStateChanged(running: Boolean) {
        // Not needed
    }

    private fun handleAppEntries(entries: List<ApplicationsState.AppEntry>) {
        val sections = ArrayList<String>()
        val positions = ArrayList<Int>()
        val pm = activity!!.packageManager
        var lastSectionIndex: String? = null
        var offset = 0

        for (i in entries.indices) {
            val info = entries[i].info
            val label = info.loadLabel(pm) as String
            val sectionIndex: String

            sectionIndex = when {
                !info.enabled -> "--"
                TextUtils.isEmpty(label) -> ""
                else -> label.substring(0, 1).uppercase()
            }

            if (lastSectionIndex == null || sectionIndex != lastSectionIndex) {
                sections.add(sectionIndex)
                positions.add(offset)
                lastSectionIndex = sectionIndex
            }

            offset++
        }

        mAllPackagesAdapter.setEntries(entries, sections, positions)
        mEntryMap.clear()
        for (e in entries) {
            mEntryMap[e.info.packageName] = e
        }
    }

    private fun rebuild() {
        mSession.rebuild(mActivityFilter, ApplicationsState.ALPHA_COMPARATOR)
    }

    private fun getStateDrawable(state: Int): Int {
        return when (state) {
            RefreshUtils.STATE_STANDARD -> R.drawable.ic_refresh_60
            RefreshUtils.STATE_EXTREME -> R.drawable.ic_refresh_120
            RefreshUtils.STATE_DEFAULT -> R.drawable.ic_refresh_default
            else -> R.drawable.ic_refresh_default
        }
    }

    private inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.app_name)
        val mode: Spinner = view.findViewById(R.id.app_mode)
        val icon: ImageView = view.findViewById(R.id.app_icon)
        val stateIcon: ImageView = view.findViewById(R.id.state)
        val rootView: View = view
    }

    private inner class ModeAdapter(context: Context) : BaseAdapter() {

        private val inflater = LayoutInflater.from(context)
        private val items = intArrayOf(
            R.string.refresh_default,
            R.string.refresh_standard,
            R.string.refresh_extreme
        )

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): Any = items[position]

        override fun getItemId(position: Int): Long = 0

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view: TextView = if (convertView != null) {
                convertView as TextView
            } else {
                inflater.inflate(
                    android.R.layout.simple_spinner_dropdown_item,
                    parent,
                    false
                ) as TextView
            }

            view.setText(items[position])
            view.textSize = 14f

            return view
        }
    }

    private inner class AllPackagesAdapter(context: Context) :
        RecyclerView.Adapter<ViewHolder>(),
        AdapterView.OnItemSelectedListener,
        SectionIndexer {

        internal var mEntries: List<ApplicationsState.AppEntry> = ArrayList()
        private lateinit var mSections: Array<String>
        private lateinit var mPositions: IntArray
        private val mActivityFilter = ActivityFilter(context.packageManager)

        override fun getItemCount(): Int = mEntries.size

        override fun getItemId(position: Int): Long = mEntries[position].id

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.refresh_list_item, parent, false)
            )
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val context = holder.itemView.context
            val entry = mEntries[position]

            holder.mode.adapter = ModeAdapter(context)
            holder.mode.onItemSelectedListener = this
            holder.title.text = entry.label
            holder.title.setOnClickListener { holder.mode.performClick() }
            mApplicationsState.ensureIcon(entry)
            holder.icon.setImageDrawable(entry.icon)
            val packageState = mRefreshUtils.getStateForPackage(entry.info.packageName)
            holder.mode.setSelection(packageState, false)
            holder.mode.tag = entry
            holder.stateIcon.setImageResource(getStateDrawable(packageState))
        }

        fun setEntries(
            entries: List<ApplicationsState.AppEntry>,
            sections: List<String>,
            positions: List<Int>
        ) {
            mEntries = entries
            mSections = sections.toTypedArray()
            mPositions = IntArray(positions.size)
            for (i in positions.indices) {
                mPositions[i] = positions[i]
            }
            notifyDataSetChanged()
        }

        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            val entry = parent?.tag as ApplicationsState.AppEntry
            val currentState = mRefreshUtils.getStateForPackage(entry.info.packageName)
            if (currentState != position) {
                mRefreshUtils.writePackage(entry.info.packageName, position)
                notifyDataSetChanged()
            }
        }

        override fun onNothingSelected(parent: AdapterView<*>?) {
            // Not needed
        }

        override fun getPositionForSection(section: Int): Int {
            return if (section < 0 || section >= mSections.size) {
                -1
            } else {
                mPositions[section]
            }
        }

        override fun getSectionForPosition(position: Int): Int {
            if (position < 0 || position >= itemCount) {
                return -1
            }

            val index = Arrays.binarySearch(mPositions, position)

            /*
             * Consider this example: section positions are 0, 3, 5; the supplied
             * position is 4. The section corresponding to position 4 starts at
             * position 3, so the expected return value is 1. Binary search will not
             * find 4 in the array and thus will return -insertPosition-1, i.e. -3.
             * To get from that number to the expected value of 1 we need to negate
             * and subtract 2.
             */
            return if (index >= 0) index else -index - 2
        }

        override fun getSections(): Array<String> = mSections
    }

    private inner class ActivityFilter(private val mPackageManager: PackageManager) :
        ApplicationsState.AppFilter {

        private val mLauncherResolveInfoList = ArrayList<String>()

        init {
            updateLauncherInfoList()
        }

        fun updateLauncherInfoList() {
            val i = Intent(Intent.ACTION_MAIN)
            i.addCategory(Intent.CATEGORY_LAUNCHER)
            val resolveInfoList = mPackageManager.queryIntentActivities(i, 0)

            synchronized(mLauncherResolveInfoList) {
                mLauncherResolveInfoList.clear()
                for (ri in resolveInfoList) {
                    mLauncherResolveInfoList.add(ri.activityInfo.packageName)
                }
            }
        }

        override fun init() {
            // Not needed
        }

        override fun filterApp(entry: ApplicationsState.AppEntry?): Boolean {
            var show = !mAllPackagesAdapter.mEntries.map { it.info.packageName }
                .contains(entry?.info?.packageName)
            if (show) {
                synchronized(mLauncherResolveInfoList) {
                    show = mLauncherResolveInfoList.contains(entry?.info?.packageName)
                }
            }
            return show
        }
    }
}
