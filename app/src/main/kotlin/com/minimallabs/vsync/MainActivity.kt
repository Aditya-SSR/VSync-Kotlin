package com.minimallabs.vsync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.minimallabs.vsync.core.App
import com.minimallabs.vsync.core.AppUpdates
import com.minimallabs.vsync.feature.academics.AcademicsTab
import com.minimallabs.vsync.feature.account.AccountTab
import com.minimallabs.vsync.feature.auth.AuthFailureDialog
import com.minimallabs.vsync.feature.auth.LoginScreen
import com.minimallabs.vsync.feature.auth.OnboardingScreen
import com.minimallabs.vsync.feature.auth.OtpOverlay
import com.minimallabs.vsync.feature.auth.OtpPrompt
import com.minimallabs.vsync.feature.calendar.CalendarRepo
import com.minimallabs.vsync.feature.dock.DockSettings
import com.minimallabs.vsync.feature.dock.ThumbDock
import com.minimallabs.vsync.feature.home.HomeTab
import com.minimallabs.vsync.feature.home.Milestones
import com.minimallabs.vsync.feature.mess.Mess
import com.minimallabs.vsync.feature.timetable.TimetableTab
import com.minimallabs.vsync.feature.widget.ClassWidget
import com.minimallabs.vsync.ui.FloatingTabBar
import com.minimallabs.vsync.ui.GlassRoot
import com.minimallabs.vsync.ui.LocalGlass
import com.minimallabs.vsync.ui.LocalNav
import com.minimallabs.vsync.ui.Nav
import com.minimallabs.vsync.ui.NavHostLayer
import com.minimallabs.vsync.ui.TabItem
import com.minimallabs.vsync.ui.VSyncTheme
import com.minimallabs.vsync.ui.isDark

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!attached) {
            attached = true
            OtpPrompt.attach()
            Milestones.load()
            DockSettings.load()
            Mess.load()
            App.logoutHooks.add { CalendarRepo.reset(); Milestones.clear(); ClassWidget.refresh(App.context) }
        }
        AppUpdates.check(this)
        setContent {
            val dark = isDark(App.prefs, isSystemInDarkTheme())
            LaunchedEffect(dark) {
                // Edge to edge, transparent bars; only the icon brightness follows the theme.
                val clear = Color.Transparent.toArgb()
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear),
                )
            }
            VSyncTheme(App.prefs) { Root() }
        }
    }

    override fun onStop() {
        super.onStop()
        ClassWidget.refresh(this)
    }

    private companion object { var attached = false }
}

/** Which tab is showing; the dock and shortcuts set it too. */
object Tabs {
    var index by androidx.compose.runtime.mutableIntStateOf(0)
}

@Composable
private fun tabItems() = listOf(
    TabItem("For You", com.minimallabs.vsync.ui.Lucide.House),
    TabItem("Timetable", com.minimallabs.vsync.ui.Lucide.CalendarDays),
    TabItem("Academics", com.minimallabs.vsync.ui.Lucide.BookOpen),
    TabItem("Account", com.minimallabs.vsync.ui.Lucide.UserRound),
)

@Composable
private fun Root() {
    val nav = remember { Nav() }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { App.toasts.collect { snack.showSnackbar(it) } }
    val signedIn = App.user != null
    LaunchedEffect(signedIn) { if (!signedIn) { nav.popToRoot(); Tabs.index = 0 } }
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val dockOn = signedIn && DockSettings.enabled

    CompositionLocalProvider(LocalNav provides nav) {
        Box(Modifier.fillMaxSize()) {
            Crossfade(
                when { signedIn -> 2; App.prefs.onboardingSeen -> 1; else -> 0 }, label = "root",
            ) { screen ->
                when (screen) {
                    0 -> OnboardingScreen()
                    1 -> LoginScreen()
                    else -> MainShell(keyboardOpen)
                }
            }
            if (signedIn) NavHostLayer(nav)
            if (dockOn && (!keyboardOpen || OtpPrompt.state.isActive)) ThumbDock()
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 92.dp))
            // Above every page, like the Flutter overlay mounted over the Navigator.
            OtpOverlay(dockActive = dockOn)
            AuthFailureDialog()
        }
    }
}

@Composable
private fun MainShell(keyboardOpen: Boolean) {
    val holder = rememberSaveableStateHolder()
    val nav = LocalNav.current
    BackHandler(enabled = Tabs.index != 0 && nav.stack.isEmpty()) { Tabs.index = 0 }
    LaunchedEffect(Unit) { runCatching { CalendarRepo.ensureLoaded() } }
    GlassRoot {
        val content = rememberLayerBackdrop()
        val glassOn = LocalGlass.current.enabled
        Box(Modifier.fillMaxSize().layerBackdrop(content)) {
            holder.SaveableStateProvider(Tabs.index) {
                when (Tabs.index) {
                    0 -> HomeTab()
                    1 -> TimetableTab()
                    2 -> AcademicsTab()
                    else -> AccountTab()
                }
            }
        }
        AnimatedVisibility(
            !keyboardOpen, Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
        ) {
            FloatingTabBar(tabItems(), Tabs.index, if (glassOn) content else null, Modifier.navigationBarsPadding().padding(bottom = 10.dp)) { Tabs.index = it }
        }
    }
}

