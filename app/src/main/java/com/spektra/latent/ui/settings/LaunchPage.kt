package com.spektra.latent.ui.settings

import android.content.Context

internal enum class LaunchPage(val label: String) {
    Workbench("工作台"), Camera("相机");

    fun save(context: Context) {
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putString(KEY, name).apply()
    }

    companion object {
        private const val KEY = "launchPage"

        fun saved(context: Context): LaunchPage {
            val value = context.getSharedPreferences("appearance", Context.MODE_PRIVATE).getString(KEY, null)
            return entries.firstOrNull { it.name == value } ?: Workbench
        }
    }
}
