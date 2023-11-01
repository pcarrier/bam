/*
 * This source is part of the
 *      _____  ___   ____
 *  __ / / _ \/ _ | / __/___  _______ _
 * / // / , _/ __ |/ _/_/ _ \/ __/ _ `/
 * \___/_/|_/_/ |_/_/ (_)___/_/  \_, /
 *                              /___/
 * repository.
 *
 * Copyright (C) 2023-present Benoit 'BoD' Lubek (BoD@JRAF.org)
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
package org.jraf.android.a.data

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Drawable
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import org.jraf.android.a.util.DIFFERENT
import org.jraf.android.a.util.logw

class ShortcutRepository(context: Context) {
    suspend fun observeShortcutsChanged(onShortcutsChanged: () -> Unit) {
        shortcutChanged.drop(1).collect {
            onShortcutsChanged()
        }
    }

    private val launcherApps: LauncherApps = context.getSystemService(LauncherApps::class.java)

    class Shortcut(
        val shortcutInfo: ShortcutInfo,
        val drawable: Drawable,
    ) {
        val id: String = shortcutInfo.id
        val label: String = shortcutInfo.shortLabel.toString()
    }

    suspend fun getAllShortcuts(packageNames: List<String>): List<Shortcut> = withContext(Dispatchers.IO) {
        if (!launcherApps.hasShortcutHostPermission()) {
            emptyList()
        } else {
            packageNames.flatMap { packageName ->
                val shortcutQuery = LauncherApps.ShortcutQuery()
                shortcutQuery.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
                shortcutQuery.setPackage(packageName)
                (launcherApps.getShortcuts(shortcutQuery, Process.myUserHandle()) ?: emptyList())
                    .filter {
                        it.isEnabled
                    }
                    .mapNotNull {
                        Shortcut(
                            shortcutInfo = it,
                            drawable = launcherApps.getShortcutIconDrawable(it, 0) ?: return@mapNotNull null
                        )
                    }
            }
        }
    }

    fun launchShortcut(shortcut: Shortcut) {
        try {
            launcherApps.startShortcut(shortcut.shortcutInfo, null, null)
        } catch (e: Exception) {
            logw(e, "Could not launch shortcut")
        }
    }
}

fun notifyShortcutsChanged() {
    shortcutChanged.value = DIFFERENT
}

private val shortcutChanged: MutableStateFlow<Any> = MutableStateFlow(DIFFERENT)
