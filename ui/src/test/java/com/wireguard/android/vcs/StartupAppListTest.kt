package com.wireguard.android.vcs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupAppListTest {
    private fun app(name: String, system: Boolean? = false) =
        StartupApp(name, "test.$name", system, StartupAppStatus.PERMISSION_ONLY, false, false)

    @Test fun paginationMakesEveryCollectedEntryReachableBeyondTheOld128Limit() {
        val original = (1..205).map { app("App$it") }
        val first = StartupAppList.page(original, StartupAppSelection())
        assertEquals(11, first.pages)
        val visited = (0 until first.pages).flatMap { StartupAppList.page(original, StartupAppSelection(pageIndex = it)).apps }
        assertEquals(original, visited)
        assertEquals(205, visited.map { it.packageName }.distinct().size)
        assertTrue((0 until first.pages).all { StartupAppList.page(original, StartupAppSelection(pageIndex = it)).apps.size <= 20 })
    }

    @Test fun typeFiltersKeepUnknownAppTypesVisibleOnlyInAllApps() {
        val apps = listOf(app("Installed"), app("System", true), app("Unknown", null))
        assertEquals(apps, StartupAppList.page(apps, StartupAppSelection()).apps)
        assertEquals(listOf(apps[0]), StartupAppList.page(apps, StartupAppSelection(filter = StartupAppFilter.INSTALLED)).apps)
        assertEquals(listOf(apps[1]), StartupAppList.page(apps, StartupAppSelection(filter = StartupAppFilter.SYSTEM)).apps)
    }

    @Test fun searchCombinesWithTypeWithoutChangingCollectedEvidence() {
        val apps = listOf(app("Mail"), app("MailSystem", true), app("Other"))
        val page = StartupAppList.page(apps, StartupAppSelection(query = "  MAIL  ", filter = StartupAppFilter.INSTALLED))
        assertEquals(listOf(apps[0]), page.apps)
        assertEquals(1, page.matching)
        assertEquals(3, page.collected)
        assertEquals(listOf(apps[2]), StartupAppList.page(apps, StartupAppSelection(query = "TEST.OTHER")).apps)
        assertEquals(StartupAppStatus.PERMISSION_ONLY, page.apps.single().status)
    }

    @Test fun stalePageAndEmptyMatchesNeverHideAnAvailableMatchingPage() {
        val apps = (1..25).map { app("App$it") }
        assertEquals(1, StartupAppList.page(apps, StartupAppSelection(pageIndex = Int.MAX_VALUE)).index)
        assertEquals(0, StartupAppList.page(apps, StartupAppSelection(pageIndex = -1)).index)
        val empty = StartupAppList.page(apps, StartupAppSelection(query = "missing", pageIndex = 99))
        assertTrue(empty.apps.isEmpty())
        assertEquals(0, empty.pages)
        assertEquals(25, empty.collected)
    }
}
