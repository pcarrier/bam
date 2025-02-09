/*
 * This source is part of the
 *      _____  ___   ____
 *  __ / / _ \/ _ | / __/___  _______ _
 * / // / , _/ __ |/ _/_/ _ \/ __/ _ `/
 * \___/_/|_/_/ |_/_/ (_)___/_/  \_, /
 *                              /___/
 * repository.
 *
 * Copyright (C) 2022-present Benoit 'BoD' Lubek (BoD@JRAF.org)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.pcarrier.bam.ui.main

import android.app.Application
import android.app.SearchManager
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pcarrier.bam.data.AppRepository
import com.pcarrier.bam.data.LaunchItemRepository
import com.pcarrier.bam.data.ShortcutRepository
import com.pcarrier.bam.util.DIFFERENT
import com.pcarrier.bam.util.containsIgnoreAccents
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private var allLaunchItems: MutableStateFlow<List<LaunchItem>> = MutableStateFlow(emptyList())

    private val launchItemRepository = LaunchItemRepository(application)
    private val appRepository = AppRepository(application, onPackagesChanged = ::refreshAllLaunchItems)
    private val shortcutRepository = ShortcutRepository(application)

    private val counters: MutableStateFlow<Map<String, Long>> = MutableStateFlow(emptyMap())

    private val deletedLaunchItems = launchItemRepository.getDeletedItems()

    init {
        viewModelScope.launch {
            launch {
                launchItemRepository.counters.collect {
                    counters.value = it
                }
            }

            shortcutRepository.observeShortcutsChanged {
                refreshAllLaunchItems()
            }
        }

        refreshAllLaunchItems()
    }

    val searchQuery = MutableStateFlow("")
    val filteredLaunchItems: StateFlow<List<LaunchItem>> =
        combine(
            allLaunchItems,
            searchQuery,
            counters,
            deletedLaunchItems,
        ) { allLaunchedItems, verbatimQuery, counters, deletedLaunchItems ->
            val query = verbatimQuery.trim()
            allLaunchedItems
                .map {
                    if (it is AppLaunchItem && counters[it.id] == -1L) {
                        it.copy(isDeprioritized = true)
                    } else {
                        it
                    }
                }
                .filterNot { it.id in deletedLaunchItems }
                .filter { it.matchesFilter(query) }
                .sortedByDescending {
                    counters[it.id] ?: 0
                }
        }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val isKeyboardWebSearchActive: Flow<Boolean> = filteredLaunchItems.map { it.isEmpty() }

    val intentToStart = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val scrollUp: MutableStateFlow<Any> = MutableStateFlow(DIFFERENT)

    fun onSearchQueryChange(query: String) {
        searchQuery.value = query
        scrollUp.value = DIFFERENT
    }

    fun onLaunchItemPrimaryAction(launchedItem: LaunchItem) {
        when (launchedItem) {
            is AppLaunchItem -> intentToStart.tryEmit(launchedItem.launchAppIntent)
            is ShortcutLaunchItem -> shortcutRepository.launchShortcut(launchedItem.shortcut)
        }
        viewModelScope.launch {
            // Add a delay so the reordering animation isn't distracting
            delay(1000)
            launchItemRepository.recordLaunchedItem(launchedItem.id)
        }
    }

    fun onLaunchItemSecondaryAction(launchedItem: LaunchItem) {
        when (launchedItem) {
            is AppLaunchItem -> intentToStart.tryEmit(launchedItem.launchAppDetailsIntent)
            is ShortcutLaunchItem -> viewModelScope.launch { launchItemRepository.deleteItem(launchedItem.id) }
        }
    }

    fun onLaunchItemTertiaryAction(launchedItem: LaunchItem) {
        viewModelScope.launch {
            if (launchedItem.isDeprioritized) {
                launchItemRepository.undeprioritizeItem(launchedItem.id)
            } else {
                launchItemRepository.deprioritizeItem(launchedItem.id)
            }
        }
    }

    fun resetSearchQuery() {
        onSearchQueryChange("")
    }

    private suspend fun getAllLaunchItems(): List<LaunchItem> {
        val allApps = appRepository.getAllApps()
        val allShortcuts = shortcutRepository.getAllShortcuts(allApps.map { it.packageName })
        return allApps.map { it.toAppLaunchItem() } +
                allShortcuts.map { it.toShortcutLaunchItem() }
    }

    private fun refreshAllLaunchItems() {
        viewModelScope.launch {
            allLaunchItems.value = getAllLaunchItems()
        }
    }

    fun onWebSearchClick() {
        val intent = Intent(Intent.ACTION_WEB_SEARCH)
            .putExtra(SearchManager.QUERY, searchQuery.value)
        intentToStart.tryEmit(intent)
    }

    fun onKeyboardActionButtonClick() {
        val launchItems = filteredLaunchItems.value
        if (searchQuery.value.isBlank()) return
        if (launchItems.isEmpty()) {
            onWebSearchClick()
        } else {
            onLaunchItemPrimaryAction(launchItems.first())
        }
    }

    sealed interface LaunchItem {
        val label: String
        val drawable: Drawable
        val id: String
        val isDeprioritized: Boolean

        fun matchesFilter(query: String): Boolean
    }

    data class AppLaunchItem(
        override val label: String,
        private val packageName: String,
        private val activityName: String,
        override val drawable: Drawable,
        override val isDeprioritized: Boolean,
    ) : LaunchItem {
        override val id = "$packageName/$activityName"

        val launchAppIntent: Intent
            get() = Intent()
                .apply { setClassName(packageName, activityName) }
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

        val launchAppDetailsIntent: Intent
            get() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$packageName"))

        override fun matchesFilter(query: String): Boolean {
            return label.containsIgnoreAccents(query) ||
                    packageName.contains(query, true)
        }
    }

    private fun AppRepository.App.toAppLaunchItem(): AppLaunchItem {
        return AppLaunchItem(
            label = label,
            packageName = packageName,
            activityName = activityName,
            drawable = drawable,
            isDeprioritized = false,
        )
    }

    data class ShortcutLaunchItem(
        override val label: String,
        override val drawable: Drawable,
        val shortcut: ShortcutRepository.Shortcut,
    ) : LaunchItem {
        override val id = "shortcut/${shortcut.id}"

        override fun matchesFilter(query: String): Boolean {
            return label.containsIgnoreAccents(query)
        }

        override val isDeprioritized: Boolean = false
    }

    private fun ShortcutRepository.Shortcut.toShortcutLaunchItem(): ShortcutLaunchItem {
        return ShortcutLaunchItem(
            label = label,
            drawable = drawable,
            shortcut = this,
        )
    }
}
