package com.minimallabs.vsync.feature.auth

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.minimallabs.vsync.core.App
import com.minimallabs.vsync.ui.Detail
import com.minimallabs.vsync.ui.GlassKind
import com.minimallabs.vsync.ui.GlassRoot
import com.minimallabs.vsync.ui.LocalTones
import com.minimallabs.vsync.ui.Logo
import com.minimallabs.vsync.ui.PrimaryButton
import com.minimallabs.vsync.ui.TextAction
import com.minimallabs.vsync.ui.glass
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

private class Slide(val title: String, val body: String, val icons: List<ImageVector>)

private val SLIDES = listOf(
    Slide("V-Sync", "Your VTOP, made calm.", emptyList()),
    Slide("Everything, at a glance", "Attendance, timetable, marks, exams and the mess menu. One quiet place.",
        listOf(Icons.AutoMirrored.Outlined.FactCheck, Icons.Outlined.Schedule, Icons.Outlined.School, Icons.Outlined.Restaurant)),
    Slide("Yours alone", "Your login stays encrypted on this phone and is only ever sent to VTOP.",
        listOf(Icons.Outlined.Lock)),
)

/** Three quiet slides before the first sign-in. */
@Composable
fun OnboardingScreen() {
    val pager = rememberPagerState { SLIDES.size }
    val scope = rememberCoroutineScope()
    fun finish() = App.updatePrefs { it.copy(onboardingSeen = true) }
    GlassRoot {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp)) {
            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                if (pager.currentPage < SLIDES.lastIndex) TextAction("Skip", color = LocalTones.current.secondaryLabel) { finish() }
            }
            HorizontalPager(pager, Modifier.weight(1f)) { page ->
                val offset = ((pager.currentPage - page) + pager.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
                Column(
                    Modifier.fillMaxSize().graphicsLayer { alpha = 1f - offset * 0.8f; translationY = offset * 40f },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    val s = SLIDES[page]
                    if (page == 0) {
                        Box(Modifier.size(132.dp).glass(RoundedCornerShape(38.dp), GlassKind.Control), contentAlignment = Alignment.Center) {
                            Logo(64.dp)
                        }
                        Spacer(Modifier.height(36.dp))
                        Text(s.title, style = MaterialTheme.typography.displayMedium)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            for (icon in s.icons) Box(Modifier.size(64.dp).glass(RoundedCornerShape(20.dp), GlassKind.Control), contentAlignment = Alignment.Center) {
                                Icon(icon, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.onBackground)
                            }
                        }
                        Spacer(Modifier.height(36.dp))
                        Text(s.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(s.body, style = MaterialTheme.typography.bodyLarge, color = LocalTones.current.secondaryLabel,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 22.dp), horizontalArrangement = Arrangement.Center) {
                repeat(SLIDES.size) { i ->
                    val on = i == pager.currentPage
                    val w by animateDpAsState(if (on) 22.dp else 7.dp, label = "dot")
                    val a by animateFloatAsState(if (on) 1f else 0.3f, label = "dotA")
                    Box(Modifier.padding(horizontal = 3.dp).height(7.dp).width(w).clip(CircleShape).alpha(a).background(MaterialTheme.colorScheme.onBackground))
                }
            }
            val last = pager.currentPage == SLIDES.lastIndex
            PrimaryButton(if (last) "Get started" else "Continue", Modifier.fillMaxWidth()) {
                if (last) finish() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
            }
            Spacer(Modifier.height(8.dp))
            Detail("Not affiliated with VIT-AP University.", Modifier.align(Alignment.CenterHorizontally))
        }
    }
}
