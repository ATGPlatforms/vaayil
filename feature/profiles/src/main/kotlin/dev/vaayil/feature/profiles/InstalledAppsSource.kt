package dev.vaayil.feature.profiles

import android.content.Context

data class AppInfo(val packageName: String, val label: String)

class InstalledAppsSource(private val context: Context) {
    fun load(includeSystem: Boolean = false): List<AppInfo> {
        val pm = context.packageManager
        val self = context.packageName
        return pm.getInstalledPackages(0)
            .asSequence()
            .filter { it.packageName != self }
            .filter { includeSystem || pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { AppInfo(it.packageName, it.applicationInfo?.loadLabel(pm)?.toString() ?: it.packageName) }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
}
