package com.minimallabs.vsync.feature.dock

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.minimallabs.vsync.Tabs
import com.minimallabs.vsync.core.App
import com.minimallabs.vsync.feature.academics.AttendancePage
import com.minimallabs.vsync.feature.academics.BiometricPage
import com.minimallabs.vsync.feature.academics.ExamSchedulePage
import com.minimallabs.vsync.feature.academics.FacultyPage
import com.minimallabs.vsync.feature.academics.GradesPage
import com.minimallabs.vsync.feature.academics.MarksPage
import com.minimallabs.vsync.feature.auth.OtpChallenge
import com.minimallabs.vsync.feature.auth.OtpPrompt
import com.minimallabs.vsync.feature.calendar.CalendarPage
import com.minimallabs.vsync.feature.da.DigitalAssignmentPage
import com.minimallabs.vsync.feature.outing.OutingPage
import com.minimallabs.vsync.ui.GlassKind
import com.minimallabs.vsync.ui.LocalNav
import com.minimallabs.vsync.ui.glass
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

// ------------------------------------------------------------------ settings

class DockShortcut(val id: String, val label: String, val icon: ImageVector, val page: @Composable () -> Unit)

object DockShortcuts {
    val all = listOf(
        DockShortcut("attendance", "Attendance", Icons.AutoMirrored.Outlined.FactCheck) { AttendancePage() },
        DockShortcut("marks", "Marks", Icons.Outlined.Checklist) { MarksPage() },
        DockShortcut("exams", "Exam Schedule", Icons.AutoMirrored.Outlined.EventNote) { ExamSchedulePage() },
        DockShortcut("calendar", "Academic Calendar", Icons.Outlined.CalendarMonth) { CalendarPage() },
        DockShortcut("grades", "Grades", Icons.Outlined.School) { GradesPage() },
        DockShortcut("biometric", "Biometric Log", Icons.Outlined.Fingerprint) { BiometricPage() },
        DockShortcut("assignments", "Digital Assignments", Icons.Outlined.UploadFile) { DigitalAssignmentPage() },
        DockShortcut("outing", "Outing", Icons.Outlined.FlightTakeoff) { OutingPage() },
        DockShortcut("faculty", "Faculty Info", Icons.Outlined.Groups) { FacultyPage() },
    )
    fun byId(id: String) = all.firstOrNull { it.id == id } // never rename an id: they are saved
}

/** Thumb Dock settings (SharedPreferences, same keys as the Flutter app). */
object DockSettings {
    val locked = listOf("attendance", "marks")
    private val defaults = listOf("attendance", "marks", "exams")
    const val MAX = 5

    var enabled by mutableStateOf(false); private set
    var rightSide by mutableStateOf(true); private set
    var y by mutableStateOf(0.55f); private set
    var shortcuts by mutableStateOf(defaults); private set

    fun load() {
        val sp = App.store.sp
        enabled = sp.getBoolean("thumb_dock_enabled_v1", false)
        rightSide = sp.getBoolean("thumb_dock_right_v1", true)
        y = sp.getFloat("thumb_dock_y_v1", 0.55f).coerceIn(0f, 1f)
        shortcuts = normalise(sp.getString("thumb_dock_shortcuts_v1", null)?.split(',')?.filter { it.isNotBlank() } ?: defaults)
    }

    private fun save() = App.store.sp.edit()
        .putBoolean("thumb_dock_enabled_v1", enabled).putBoolean("thumb_dock_right_v1", rightSide)
        .putFloat("thumb_dock_y_v1", y).putString("thumb_dock_shortcuts_v1", shortcuts.joinToString(",")).apply()

    fun normalise(ids: List<String>): List<String> {
        val out = ArrayList(locked)
        for (id in ids) if (id !in out && out.size < MAX && DockShortcuts.byId(id) != null) out.add(id)
        return out
    }

    fun turn(v: Boolean) { enabled = v; save() }
    fun setPosition(right: Boolean, fraction: Float) { rightSide = right; y = fraction.coerceIn(0f, 1f); save() }
    fun toggle(id: String) {
        if (id in locked) return
        shortcuts = if (id in shortcuts) normalise(shortcuts - id) else if (shortcuts.size < MAX) normalise(shortcuts + id) else shortcuts
        save()
    }
}

// ------------------------------------------------------------------ geometry

private sealed interface Hit { data class Main(val i: Int) : Hit; data class Sub(val i: Int) : Hit }

/** Pure layout of the open dock (draw AND hit-test from it). Pixel units. */
private class DockLayout(
    val width: Float, val height: Float, val topInset: Float, val bottomInset: Float,
    val right: Boolean, val handleY: Float, val mainCount: Int, val academicsIndex: Int, val subCount: Int, val px: Float,
) {
    val item = 50 * px; val gap = 12 * px; val sub = 46 * px; val subGap = 9 * px; val subLead = 14 * px
    val columnX = if (right) width - 50 * px else 50 * px
    private val inward = if (right) -1f else 1f
    val columnHeight = mainCount * item + (mainCount - 1).coerceAtLeast(0) * gap
    val columnTop: Float
        get() {
            val lo = topInset + 56 * px
            val hi = height - bottomInset - columnHeight - 24 * px
            return (handleY - columnHeight / 2).coerceIn(lo, maxOf(lo, hi))
        }
    fun main(i: Int) = Offset(columnX, columnTop + item / 2 + i * (item + gap))
    fun sub(j: Int) = Offset(columnX + inward * (item / 2 + subLead + sub / 2 + j * (sub + subGap)), main(academicsIndex).y)

    fun hit(p: Offset, subsOpen: Boolean): Hit? {
        if (subsOpen && subCount > 0) {
            val row = main(academicsIndex).y
            if (abs(p.y - row) <= 30 * px && inward * (p.x - columnX) > item / 2 + 4 * px) {
                val best = (0 until subCount).minBy { abs(p.x - sub(it).x) }
                return if (abs(p.x - sub(best).x) <= sub / 2 + subGap / 2 + 4 * px) Hit.Sub(best) else null
            }
        }
        if (abs(p.x - columnX) > 48 * px) return null
        if (p.y < columnTop - 12 * px || p.y > columnTop + columnHeight + 12 * px) return null
        return Hit.Main((0 until mainCount).minBy { abs(p.y - main(it).y) })
    }
}

private class MainItem(val id: String, val label: String, val icon: ImageVector, val tab: Int?)

// ------------------------------------------------------------------ the dock

/**
 * The Thumb Dock: a handle on a screen edge.
 *  - Hold and slide onto a page, let go to open it. Sliding onto Academics unfolds its shortcuts.
 *  - Tap: the menu stays open for a tap.
 *  - Drag the handle to move it; it settles on the nearer edge.
 * A pending OTP adds a key item, and the handle shows its countdown.
 */
@Composable
fun ThumbDock() {
    val nav = LocalNav.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val px = density.density
    val otp = OtpPrompt.state
    val otpPending = otp.isActive && !otp.isSucceeded

    val mains = buildList {
        if (otpPending) add(MainItem("otp", "Enter OTP", Icons.Outlined.VpnKey, null))
        add(MainItem("home", "For You", Icons.Outlined.AutoAwesome, 0))
        add(MainItem("timetable", "Timetable", Icons.Outlined.Schedule, 1))
        add(MainItem("academics", "Academics", Icons.Outlined.School, 2))
        add(MainItem("account", "Account", Icons.Outlined.Person, 3))
    }
    val academicsIndex = mains.indexOfFirst { it.id == "academics" }
    val shortcuts = DockSettings.shortcuts.mapNotNull { DockShortcuts.byId(it) }

    var open by remember { mutableStateOf(false) }
    var holdMode by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var hover by remember { mutableStateOf<Hit?>(null) }
    var dragPos by remember { mutableStateOf<Offset?>(null) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(open) { progress.animateTo(if (open) 1f else 0f, spring(dampingRatio = 0.78f, stiffness = 520f)) }

    fun close() { open = false; holdMode = false; expanded = false; hover = null }

    fun choose(hit: Hit, sticky: Boolean) {
        when (hit) {
            is Hit.Sub -> { close(); nav.popToRoot(); Tabs.index = 2; nav.push(shortcuts[hit.i].page) }
            is Hit.Main -> {
                val item = mains[hit.i]
                when {
                    item.id == "otp" -> { close(); OtpPrompt.reopen() }
                    item.id == "academics" && sticky && shortcuts.isNotEmpty() -> {
                        nav.popToRoot(); Tabs.index = 2; expanded = true; hover = null; holdMode = false
                    }
                    else -> { close(); nav.popToRoot(); Tabs.index = item.tab ?: 0 }
                }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val topInset = 48 * px
        val bottomInset = 100 * px
        val usable = h - topInset - bottomInset
        val handleY = topInset + DockSettings.y * usable
        val right = DockSettings.rightSide
        val layout = DockLayout(w, h, topInset, bottomInset, right, handleY, mains.size, academicsIndex, shortcuts.size, px)

        fun updateHover(p: Offset) {
            val hit = layout.hit(p, expanded)
            val nextExpanded = if (hit is Hit.Main) hit.i == academicsIndex && shortcuts.isNotEmpty() else expanded
            if (hit != hover && hit != null) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            hover = hit; expanded = nextExpanded
        }

        // Scrim + menu
        if (progress.value > 0.01f) {
            Box(
                Modifier.fillMaxSize().graphicsLayer { alpha = progress.value }.background(Color.Black.copy(alpha = 0.35f))
                    .pointerInput(open, expanded) {
                        detectTapGestures { p -> val hit = layout.hit(p, expanded); if (hit != null) choose(hit, sticky = true) else close() }
                    },
            )
            val t = progress.value
            val handleOrigin = Offset(if (right) w else 0f, handleY)
            mains.forEachIndexed { i, item ->
                val c = layout.main(i)
                val pos = Offset(handleOrigin.x + (c.x - handleOrigin.x) * t, handleOrigin.y + (c.y - handleOrigin.y) * t)
                DockBubble(item.icon, item.label, hover == Hit.Main(i), pos, layout.item, t, labelOnLeft = right, showLabel = hover == Hit.Main(i))
            }
            if (expanded) shortcuts.forEachIndexed { j, s ->
                DockBubble(s.icon, s.label, hover == Hit.Sub(j), layout.sub(j), layout.sub, t, labelOnLeft = false, showLabel = hover == Hit.Sub(j), above = true)
            }
        }

        // The handle (stays mounted while the menu is open: the held finger's events route to it).
        val tabW = if (otpPending) 46.dp else 30.dp
        val tabH = 58.dp
        val tabWpx = with(density) { tabW.toPx() }
        val tabHpx = with(density) { tabH.toPx() }
        val shown = dragPos ?: Offset(if (right) w - tabWpx / 2 else tabWpx / 2, handleY)
        var handleOrigin by remember { mutableStateOf(Offset.Zero) }
        Box(
            Modifier
                .offset { IntOffset((shown.x - tabWpx / 2).roundToInt(), (shown.y - tabHpx / 2).roundToInt()) }
                .size(tabW, tabH)
                .onGloballyPositioned { handleOrigin = it.boundsInRoot().topLeft }
                .glass(RoundedCornerShape(if (right) 18.dp else 18.dp), GlassKind.Control)
                .pointerInput(right, otpPending, mains.size) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = down.position
                        var moved = false
                        var held = false
                        var holdJob: Job? = scope.launch {
                            delay(280)
                            held = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            open = true; holdMode = true; expanded = false; hover = null
                        }
                        var last = start
                        while (true) {
                            val ev = awaitPointerEvent()
                            val change = ev.changes.firstOrNull { it.id == down.id } ?: break
                            last = change.position
                            val root = handleOrigin + change.position
                            if (!change.pressed) break
                            if (held) {
                                updateHover(root)
                            } else if (!moved && (change.position - start).getDistance() > viewConfiguration.touchSlop) {
                                moved = true; holdJob?.cancel(); holdJob = null
                            }
                            if (moved && !held) dragPos = Offset(root.x, root.y)
                            if (change.positionChange() != Offset.Zero) change.consume()
                        }
                        holdJob?.cancel()
                        val upRoot = handleOrigin + last
                        when {
                            held -> {
                                val hit = hover ?: layout.hit(upRoot, expanded)
                                if (hit != null) choose(hit, sticky = false)
                                else if ((last - start).getDistance() > 14 * px) close()
                                else holdMode = false // held still: leave it open for a tap
                            }
                            moved -> {
                                val p = dragPos ?: upRoot
                                dragPos = null
                                DockSettings.setPosition(p.x > w / 2, ((p.y - topInset) / usable))
                            }
                            otpPending -> { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); OtpPrompt.reopen() }
                            else -> { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); open = true; holdMode = false }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (otpPending) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.VpnKey, "OTP", Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurface)
                    Text(if (otp.remainingSeconds > 0) OtpChallenge.formatSeconds(otp.remainingSeconds) else "OTP", style = MaterialTheme.typography.labelSmall)
                }
            } else {
                Box(Modifier.size(4.dp, 22.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), CircleShape))
            }
        }
    }
}

@Composable
private fun DockBubble(icon: ImageVector, label: String, active: Boolean, center: Offset, sizePx: Float, t: Float, labelOnLeft: Boolean, showLabel: Boolean, above: Boolean = false) {
    val density = LocalDensity.current
    val sizeDp = with(density) { sizePx.toDp() }
    val scale = (if (active) 1.12f else 1f) * (0.4f + 0.6f * t)
    Box(
        Modifier.offset { IntOffset((center.x - sizePx / 2).roundToInt(), (center.y - sizePx / 2).roundToInt()) }
            .size(sizeDp).graphicsLayer { scaleX = scale; scaleY = scale; alpha = t }
            .glass(CircleShape, GlassKind.Control, tint = if (active) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f) else null),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(22.dp), tint = if (active) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface)
    }
    if (showLabel && t > 0.9f) {
        val gap = with(density) { 12.dp.toPx() }
        Text(
            label, style = MaterialTheme.typography.labelLarge, color = Color.White,
            modifier = Modifier.offset {
                if (above) IntOffset((center.x - sizePx).roundToInt(), (center.y - sizePx - gap).roundToInt())
                else IntOffset(if (labelOnLeft) (center.x - sizePx / 2 - gap - 140 * density.density).roundToInt() else (center.x + sizePx / 2 + gap).roundToInt(), (center.y - 10 * density.density).roundToInt())
            }.padding(0.dp),
        )
    }
}
