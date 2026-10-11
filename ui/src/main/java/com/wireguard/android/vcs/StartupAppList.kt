package com.wireguard.android.vcs

enum class StartupAppFilter { ALL, INSTALLED, SYSTEM }

/** Screen-only selection; retained by the ViewModel, never written to preferences or exported. */
data class StartupAppSelection(
    val query: String = "", val filter: StartupAppFilter = StartupAppFilter.ALL, val pageIndex: Int = 0
)

data class StartupAppPage(
    val apps: List<StartupApp>, val matching: Int, val collected: Int, val index: Int, val pages: Int
)

object StartupAppList {
    const val PAGE_SIZE = 20

    fun page(apps: List<StartupApp>, selection: StartupAppSelection): StartupAppPage {
        val query = selection.query.trim()
        val matching = apps.filter { app ->
            val kindMatches = when (selection.filter) {
                StartupAppFilter.ALL -> true
                StartupAppFilter.INSTALLED -> app.systemApp == false
                StartupAppFilter.SYSTEM -> app.systemApp == true
            }
            kindMatches && (query.isEmpty() || app.label.contains(query, ignoreCase = true) ||
                app.packageName.contains(query, ignoreCase = true))
        }
        val pages = (matching.size + PAGE_SIZE - 1) / PAGE_SIZE
        val index = selection.pageIndex.coerceIn(0, (pages - 1).coerceAtLeast(0))
        return StartupAppPage(matching.drop(index * PAGE_SIZE).take(PAGE_SIZE), matching.size, apps.size, index, pages)
    }
}
