package com.minimallabs.vsync.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minimallabs.vsync.core.Failures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- navigation

class NavEntry(val id: Int, val content: @Composable () -> Unit)

/** A small page stack above the tab shell. */
@Stable
class Nav {
    val stack = mutableStateListOf<NavEntry>()
    private var next = 1
    fun push(content: @Composable () -> Unit) { stack.add(NavEntry(next++, content)) }
    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    fun popToRoot() { stack.clear() }
}

val LocalNav = staticCompositionLocalOf<Nav> { error("no nav") }

/** Shows the top page with a slide; earlier pages keep their state. */
@Composable
fun NavHostLayer(nav: Nav) {
    BackHandler(enabled = nav.stack.isNotEmpty()) { nav.pop() }
    val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val top = nav.stack.lastOrNull()
    AnimatedContent(
        targetState = top,
        transitionSpec = {
            val forward = (targetState?.id ?: 0) > (initialState?.id ?: 0)
            if (forward) (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 } + fadeOut())
            else (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { it } + fadeOut())
        },
        label = "nav",
    ) { entry ->
        if (entry != null) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).pointerInput(Unit) { detectTapGestures { } }) {
                GlassRoot { holder.SaveableStateProvider(entry.id) { entry.content() } }
            }
        }
    }
}

// ---------------------------------------------------------------- page scaffolds

/**
 * A page: back arrow beside a big title in the heading colour (as in the
 * Flutter app), then the content. Pass an empty [title] for a page that draws
 * its own top (Home, Timetable).
 */
@Composable
fun Page(
    title: String,
    actions: @Composable RowScope.() -> Unit = {},
    scroll: Boolean = true,
    padding: Dp = 20.dp,
    back: Boolean = true,
    subtitle: String? = null,
    titleStyle: androidx.compose.ui.text.TextStyle? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val nav = LocalNav.current
    Column(
        Modifier.fillMaxSize().statusBarsPadding()
            .let { if (scroll) it.verticalScroll(rememberScrollState()) else it }
            .padding(horizontal = padding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        if (title.isNotEmpty() || back) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (back) {
                    Icon(
                        Lucide.ArrowLeft, "Back",
                        Modifier.offset(x = (-8).dp).size(44.dp).clip(CircleShape).clickable { nav.pop() }.padding(10.dp),
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    title, style = titleStyle ?: MaterialTheme.typography.headlineLarge,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                actions()
            }
        }
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = LocalTones.current.secondaryLabel, modifier = Modifier.offset(y = (-6).dp))
        content()
        if (scroll) Spacer(Modifier.height(130.dp))
    }
}

/** A tab's root: same page, no back arrow. */
@Composable
fun TabScreen(title: String, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) =
    Page(title, actions = actions, back = false, subtitle = subtitle, content = content)

// ---------------------------------------------------------------- surfaces

/** The standard card: solid surfaceContainerLow, radius 24. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(20.dp),
    bordered: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth().glass(CardShape, bordered = bordered).clip(CardShape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(padding),
        content = content,
    )
}

/** A grouped list in one card; rows are separated by hairlines that start at the text. */
class GroupScope {
    internal val rows = ArrayList<@Composable () -> Unit>()
    fun row(content: @Composable () -> Unit) { rows.add(content) }
}

@Composable
fun Group(header: String? = null, footer: String? = null, build: GroupScope.() -> Unit) {
    val scope = GroupScope().apply(build)
    if (scope.rows.isEmpty()) return
    Column {
        if (header != null) Text(
            header.uppercase(), style = MaterialTheme.typography.labelMedium, color = LocalTones.current.secondaryLabel,
            modifier = Modifier.padding(start = 6.dp, bottom = 10.dp, top = 6.dp),
        )
        Column(Modifier.fillMaxWidth().glass(GroupShape).clip(GroupShape).padding(vertical = 6.dp)) {
            scope.rows.forEachIndexed { i, r ->
                if (i > 0) Box(Modifier.padding(start = 62.dp, end = 24.dp).fillMaxWidth().height(1.dp).background(LocalTones.current.separator.copy(alpha = 0.7f)))
                r()
            }
        }
        if (footer != null) Detail(footer, Modifier.padding(start = 6.dp, end = 6.dp, top = 8.dp))
    }
}

/** A list row: an outline icon, the title, optional detail, then a value, switch or chevron. */
@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    value: String? = null,
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    chevron: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val click: (() -> Unit)? = onClick ?: if (checked != null && onCheckedChange != null) ({ onCheckedChange(!checked) }) else null
    Row(
        Modifier.fillMaxWidth().let { if (click != null) it.clickable(onClick = click) else it }
            .padding(start = 20.dp, end = 18.dp, top = 15.dp, bottom = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(23.dp), tint = if (titleColor != MaterialTheme.colorScheme.onSurface) titleColor else LocalTones.current.secondaryLabel)
            Spacer(Modifier.width(19.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize), color = titleColor)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = LocalTones.current.secondaryLabel, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (value != null) {
            Spacer(Modifier.width(8.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium, color = LocalTones.current.secondaryLabel, maxLines = 1)
        }
        if (checked != null) {
            Spacer(Modifier.width(10.dp))
            IosSwitch(checked) { onCheckedChange?.invoke(it) }
        } else if (onClick != null && chevron) {
            Spacer(Modifier.width(4.dp))
            Icon(Lucide.ChevronRight, null, Modifier.size(20.dp), tint = LocalTones.current.secondaryLabel)
        }
    }
}

/** Kept for older call sites; same as [ListRow]. */
@Composable
fun SettingRow(
    title: String, subtitle: String? = null, icon: ImageVector? = null, checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null, titleColor: Color = MaterialTheme.colorScheme.onSurface, onClick: (() -> Unit)? = null,
) = ListRow(title, subtitle, icon, null, checked, onCheckedChange, titleColor, true, onClick)

/** The Flutter app's switch: a light track with a dark thumb when on. */
@Composable
fun IosSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val x by animateDpAsState(if (checked) 22.dp else 0.dp, spring(dampingRatio = 0.7f, stiffness = 600f), label = "sw")
    val track by animateColorAsState(if (checked) MaterialTheme.colorScheme.primary else LocalTones.current.fill, label = "track")
    val thumb by animateColorAsState(if (checked) MaterialTheme.colorScheme.onPrimary else LocalTones.current.secondaryLabel, label = "thumb")
    Box(
        Modifier.size(56.dp, 32.dp).clip(CircleShape).background(track)
            .let { if (!checked) it.border(1.5.dp, LocalTones.current.secondaryLabel.copy(alpha = 0.5f), CircleShape) else it }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onChange(!checked) }
            .padding(4.dp),
    ) {
        Box(Modifier.offset(x = x).size(24.dp).clip(CircleShape).background(thumb))
    }
}

/** The Flutter segmented control: a dark pill track with an accent-filled selection. */
@Composable
fun Segmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(58.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(5.dp)) {
        val w = maxWidth / labels.size
        val x by animateDpAsState(w * selected, spring(dampingRatio = 0.8f, stiffness = 500f), label = "seg")
        Box(Modifier.offset(x = x).width(w).fillMaxHeight().clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        Row(Modifier.fillMaxSize()) {
            labels.forEachIndexed { i, label ->
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                        color = if (i == selected) MaterialTheme.colorScheme.onPrimary else LocalTones.current.secondaryLabel,
                    )
                }
            }
        }
    }
}

/** Filled pill button. */
@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier.height(54.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.4f))
            .clickable(enabled = enabled && !busy, onClick = onClick).padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        else Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimary)
    }
}

/** Quiet button: a card-coloured pill. */
@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.height(54.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.4f)) }
}

/** A text action ("Manage", "Add") in the secondary colour. */
@Composable
fun TextAction(text: String, modifier: Modifier = Modifier, color: Color = LocalTones.current.secondaryLabel, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text, style = MaterialTheme.typography.titleMedium.copy(fontSize = MaterialTheme.typography.bodyLarge.fontSize),
        color = color.copy(alpha = if (enabled) 1f else 0.4f),
        modifier = modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/** A round icon button for the title row. */
@Composable
fun BarButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(22.dp), tint = LocalTones.current.secondaryLabel)
    }
}

@Composable
fun BarSpinner() {
    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
}

// ---------------------------------------------------------------- text

/** A section heading in the heading colour, with an optional action on the right. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

/** Small secondary text (Inter). */
@Composable
fun Detail(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, color: Color = LocalTones.current.secondaryLabel) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) = Detail(text, modifier, maxLines)

/** An outlined pill tag ("THEORY", "Exam"). */
@Composable
fun Pill(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
    container: Color = Color.Transparent,
    modifier: Modifier = Modifier,
) {
    Text(
        text, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 1.sp),
        color = color, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(8.dp)).background(container)
            .let { if (container == Color.Transparent) it.border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(8.dp)) else it }
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

@Composable
fun StatusPill(text: String, color: Color) = Pill(text, color = color)

@Composable
fun KeyValue(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = LocalTones.current.secondaryLabel, modifier = Modifier.padding(end = 12.dp))
        Text(value.ifBlank { "-" }, style = MaterialTheme.typography.bodyLarge, color = valueColor, textAlign = TextAlign.End, modifier = Modifier.weight(1f, fill = false))
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(LocalTones.current.separator.copy(alpha = 0.6f)))
}

@Composable
fun CenterLoader(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.5.dp) }
}

@Composable
fun EmptyState(title: String, message: String? = null, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 44.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface), textAlign = TextAlign.Center)
        if (message != null) {
            Spacer(Modifier.height(6.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = LocalTones.current.secondaryLabel, textAlign = TextAlign.Center)
        }
        if (action != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            SecondaryButton(action, onClick = onAction)
        }
    }
}

@Composable
fun ErrorLine(message: String) {
    Text(message, style = MaterialTheme.typography.bodyMedium, color = LocalStatus.current.danger, modifier = Modifier.padding(horizontal = 4.dp))
}

/** A horizontally scrolling row of choice chips. */
@Composable
fun ChipRow(options: List<String>, selected: String?, label: (String) -> String = { it }, onPick: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (o in options) {
            val on = o == selected
            Text(
                label(o), style = MaterialTheme.typography.labelLarge, maxLines = 1,
                color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clip(CircleShape).background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onPick(o) }.padding(horizontal = 16.dp, vertical = 9.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- async state

sealed interface Load<out T> {
    data object Idle : Load<Nothing>
    data object Loading : Load<Nothing>
    data class Data<T>(val value: T) : Load<T>
    data class Error(val message: String) : Load<Nothing>
}

/** Runs one request at a time and exposes it to Compose; keeps the last good value on screen. */
@Stable
class Loader<T>(private val scope: CoroutineScope, initial: T? = null) {
    var state by mutableStateOf<Load<T>>(if (initial != null) Load.Data(initial) else Load.Idle)
        private set
    var value by mutableStateOf(initial)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    val loading: Boolean get() = state is Load.Loading
    private var job: Job? = null

    fun set(v: T) { value = v; state = Load.Data(v); error = null }

    fun run(fallback: String = "An unexpected error occurred. Please try again.", onDone: (T) -> Unit = {}, block: suspend () -> T) {
        job?.cancel()
        state = Load.Loading
        error = null
        job = scope.launch {
            try {
                val v = block()
                value = v
                state = Load.Data(v)
                onDone(v)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val msg = Failures.messageFor(e, fallback)
                error = msg
                state = Load.Error(msg)
            }
        }
    }
}

@Composable
fun <T> rememberLoader(initial: T? = null): Loader<T> {
    val scope = rememberCoroutineScope()
    return remember { Loader(scope, initial) }
}
