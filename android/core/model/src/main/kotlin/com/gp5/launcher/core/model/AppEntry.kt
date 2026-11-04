package com.gp5.launcher.core.model

/** Drawer categories (apps.html segmented control). [All] is a filter, not a category of an app. */
enum class AppCategory(val label: String) { All("All"), Media("Media"), Navigation("Navigation"), Car("Car"), Tools("Tools") }

/** One launchable app, keyed by package name. */
data class AppEntry(val packageName: String, val label: String, val category: AppCategory)

/**
 * Drawer state: every launchable app (sorted by label), the dock slots and the hidden set.
 * [dock] always has [DOCK_SLOTS] entries; null is an empty slot (or a docked app that is not installed).
 */
data class AppsState(
    val apps: List<AppEntry> = emptyList(),
    val dock: List<AppEntry?> = List(DOCK_SLOTS) { null },
    val hidden: Set<String> = emptySet(),
    val loaded: Boolean = false,
) {
    val dockFull: Boolean get() = dock.none { it == null }
    fun isDocked(pkg: String): Boolean = dock.any { it?.packageName == pkg }
    val hiddenApps: List<AppEntry> get() = apps.filter { it.packageName in hidden }

    companion object {
        const val DOCK_SLOTS = 6
    }
}
