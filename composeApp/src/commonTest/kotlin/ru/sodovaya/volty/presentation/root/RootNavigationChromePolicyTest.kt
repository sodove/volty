package ru.sodovaya.volty.presentation.root

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.sodovaya.volty.domain.model.DashboardStyle

class RootNavigationChromePolicyTest {
    @Test
    fun clean_and_classic_ride_dashboards_keep_the_root_tab_bar() {
        assertTrue(bottomTabBarVisible(RootChromeDestination.RIDE, DashboardStyle.CLEAN))
        assertTrue(bottomTabBarVisible(RootChromeDestination.RIDE, DashboardStyle.CLASSIC))
    }

    @Test
    fun light_ride_dashboard_keeps_its_hud_navigation() {
        assertFalse(bottomTabBarVisible(RootChromeDestination.RIDE, DashboardStyle.LIGHT))
    }

    @Test
    fun main_non_ride_destinations_keep_the_root_tab_bar() {
        assertTrue(bottomTabBarVisible(RootChromeDestination.BATTERY, null))
        assertTrue(bottomTabBarVisible(RootChromeDestination.NEARBY, null))
        assertTrue(bottomTabBarVisible(RootChromeDestination.SETTINGS, null))
    }

    @Test
    fun group_map_has_root_chrome_but_light_ride_does_not() {
        assertTrue(bottomTabBarVisible(RootChromeDestination.GROUP_MAP, null))
        assertFalse(bottomTabBarVisible(RootChromeDestination.RIDE, DashboardStyle.LIGHT))
    }

    @Test
    fun nearby_does_not_add_a_second_map_tab() {
        assertFalse(shouldShowGroupMapTab(RootChromeDestination.NEARBY, groupMapVisible = false))
        assertTrue(shouldShowGroupMapTab(RootChromeDestination.GROUP_MAP, groupMapVisible = true))
    }

    @Test
    fun group_map_back_is_a_pop_and_never_a_runtime_reset() {
        assertTrue(groupMapBackAction(hasPreviousDestination = true).shouldPop)
        assertFalse(groupMapBackAction(hasPreviousDestination = true).clearsSocialRuntime)
    }
}
