package com.nova.ai

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Stage 10 capability discovery.
 * Finds installed apps that can handle a native/deep-link intent before Nova
 * falls back to UI automation. It does not assume that every app exposes a
 * private API or that every deep link works on every version.
 */
object CapabilityDiscovery {
    data class AppCapability(
        val label: String,
        val packageName: String,
        val canOpenWeb: Boolean,
        val canOpenCustomUri: Boolean
    )

    fun findInstalledApp(context: Context, name: String): AppCapability? {
        val pm = context.packageManager
        val normalized = name.trim().lowercase()
        if (normalized.isBlank()) return null

        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val exact = apps.firstOrNull {
            pm.getApplicationLabel(it).toString().trim().lowercase() == normalized
        }
        val partial = exact ?: apps.firstOrNull {
            pm.getApplicationLabel(it).toString().trim().lowercase().contains(normalized)
        }
        val info = partial ?: return null
        val label = pm.getApplicationLabel(info).toString()

        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
        val uriIntent = Intent(Intent.ACTION_VIEW, Uri.parse("nova://capability-test"))
        return AppCapability(
            label = label,
            packageName = info.packageName,
            canOpenWeb = pm.queryIntentActivities(webIntent.setPackage(info.packageName), 0).isNotEmpty(),
            canOpenCustomUri = pm.queryIntentActivities(uriIntent.setPackage(info.packageName), 0).isNotEmpty()
        )
    }

    fun canResolve(context: Context, uri: String, packageName: String? = null): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            if (!packageName.isNullOrBlank()) setPackage(packageName)
        }
        return context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
    }
}
