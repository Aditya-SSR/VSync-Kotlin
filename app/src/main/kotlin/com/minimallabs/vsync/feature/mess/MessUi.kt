package com.minimallabs.vsync.feature.mess

import com.minimallabs.vsync.ui.Lucide
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.minimallabs.vsync.core.App
import com.minimallabs.vsync.data.AppJson
import com.minimallabs.vsync.feature.outing.ConfirmDialog
import com.minimallabs.vsync.ui.AppCard
import com.minimallabs.vsync.ui.Detail
import com.minimallabs.vsync.ui.Group
import com.minimallabs.vsync.ui.ListRow
import com.minimallabs.vsync.ui.LocalNav
import com.minimallabs.vsync.ui.LocalStatus
import com.minimallabs.vsync.ui.LocalTones
import com.minimallabs.vsync.ui.Page
import com.minimallabs.vsync.ui.SecondaryButton
import com.minimallabs.vsync.ui.SectionTitle
import com.minimallabs.vsync.ui.Segmented
import com.minimallabs.vsync.ui.TextAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Serializable
data class MessSource(val name: String, val importedAt: Long, val readable: Boolean)

/** The saved menu, its original file, and the chosen sheet. */
object Mess {
    var menu by mutableStateOf<MessMenu?>(null); private set
    var source by mutableStateOf<MessSource?>(null); private set
    var sheet by mutableStateOf<String?>(null); private set

    private const val FILE = "mess_menu_source.xlsx"

    fun load() {
        val sp = App.store.sp
        menu = sp.getString("mess_menu_v2", null)?.let { runCatching { AppJson.decodeFromString<MessMenu>(it) }.getOrNull() }
        source = sp.getString("mess_source_v2", null)?.let { runCatching { AppJson.decodeFromString<MessSource>(it) }.getOrNull() }
            ?.takeIf { App.store.file(FILE).exists() }
        sheet = sp.getString("mess_sheet_v1", null)
    }

    fun chooseSheet(name: String) { sheet = name; App.store.sp.edit().putString("mess_sheet_v1", name).apply() }

    val activeSheet: String? get() = menu?.let { m -> sheet?.takeIf { it in m.sheetNames } ?: m.sheetNames.first() }

    /** Reads the file; keeps it either way (a sheet that does not read is shown as a table). */
    suspend fun import(bytes: ByteArray, name: String): String? = withContext(Dispatchers.IO) {
        val parsed = runCatching { MessReader.parseXlsx(bytes) }
        val readable = parsed.getOrNull()?.isReadable == true
        if (parsed.isFailure && runCatching { MessReader.rawGrid(bytes) }.getOrDefault(emptyList()).isEmpty()) {
            return@withContext "That file is not a mess menu (.xlsx) VSync can read."
        }
        App.store.file(FILE).writeBytes(bytes)
        val src = MessSource(name, System.currentTimeMillis(), readable)
        withContext(Dispatchers.Main) {
            menu = if (readable) parsed.getOrNull() else null
            source = src
            sheet = null
        }
        App.store.sp.edit()
            .putString("mess_source_v2", AppJson.encodeToString(src))
            .putString("mess_menu_v2", if (readable) AppJson.encodeToString(parsed.getOrNull()!!) else null)
            .remove("mess_sheet_v1").apply()
        if (readable) null else "VSync could not lay this one out as a menu, so the original sheet is shown instead."
    }

    fun rawGrid(): List<List<String>> = runCatching { MessReader.rawGrid(App.store.file(FILE).readBytes()) }.getOrDefault(emptyList())

    fun clear() {
        menu = null; source = null; sheet = null
        App.store.file(FILE).delete()
        App.store.sp.edit().remove("mess_menu_v2").remove("mess_source_v2").remove("mess_sheet_v1").apply()
    }
}

val MEALS = listOf("Breakfast", "Lunch", "Snacks", "Dinner")

/** The meal that is next or on now. */
fun currentMeal(now: LocalTime = LocalTime.now()): Int {
    val m = now.hour * 60 + now.minute
    return when { m < 10 * 60 + 30 -> 0; m < 15 * 60 -> 1; m < 18 * 60 + 30 -> 2; else -> 3 }
}

@Composable
private fun rememberXlsxPicker(onResult: (String?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                val (name, bytes) = withContext(Dispatchers.IO) {
                    var n = "mess-menu.xlsx"
                    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (i >= 0 && c.moveToFirst()) n = c.getString(i) ?: n
                    }
                    n to (context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0))
                }
                if (!name.lowercase().endsWith(".xlsx")) "Pick the .xlsx file (Excel) of the mess menu."
                else Mess.import(bytes, name)
            }.getOrElse { "Could not save that file" }
            onResult(result)
        }
    }
    return {
        launcher.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream", "*/*"))
    }
}

/** The Home section: today's menu, by meal, with day navigation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessSection() {
    val nav = LocalNav.current
    var note by remember { mutableStateOf<String?>(null) }
    var manage by remember { mutableStateOf(false) }
    val pick = rememberXlsxPicker { note = it; if (it != null) App.toast(it) }
    val menu = Mess.menu
    val source = Mess.source
    val today = LocalDate.now()

    val sheet = Mess.activeSheet
    SectionTitle(
        "Mess Menu",
        action = {
            // The month name (and a non-first menu) opens the menu's sheet, as in the Flutter app.
            val label = when {
                menu != null -> menu.monthName + if (sheet != null && sheet != menu.sheetNames.first()) " · $sheet" else ""
                source != null -> "Manage"
                else -> "Upload"
            }
            TextAction(label, color = MaterialTheme.colorScheme.primary) { if (menu == null && source == null) pick() else manage = true }
        },
    )
    when {
        menu == null && source != null -> AppCard(onClick = { nav.push { MessSheetPage() } }) {
            Text("Your menu file", style = MaterialTheme.typography.titleMedium)
            Detail("VSync could not lay ${source.name} out as a menu. Tap to see the original sheet.")
        }
        menu == null -> Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).border(1.2.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                .clickable(onClick = pick).padding(vertical = 26.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Lucide.Upload, null, Modifier.size(26.dp), tint = LocalTones.current.secondaryLabel)
            Spacer(Modifier.height(8.dp))
            Text("Upload your mess menu", style = MaterialTheme.typography.bodyLarge)
            Detail("The month's .xlsx from your college email.")
        }
        menu.hasEnded(today) -> AppCard {
            Text("${menu.monthName}'s menu is over", style = MaterialTheme.typography.titleMedium)
            Detail("Upload this month's .xlsx from your email when it arrives.")
            Spacer(Modifier.height(12.dp))
            SecondaryButton("Upload new menu", Modifier.fillMaxWidth(), onClick = pick)
        }
        else -> MessDay(menu, sheet)
    }
    note?.let { Detail(it, Modifier.padding(horizontal = 4.dp)) }

    if (manage) ModalBottomSheet(onDismissRequest = { manage = false }) {
        var confirm by remember { mutableStateOf(false) }
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Mess menu", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp))
            Detail(source?.let { "From ${it.name}" } ?: "No menu uploaded yet", Modifier.padding(start = 4.dp))
            if (menu != null && menu.sheetNames.size > 1) Group("Show menu") {
                for (s in menu.sheetNames) row { ListRow(s, checked = null, value = if (s == sheet) "✓" else null, chevron = false, onClick = { Mess.chooseSheet(s); manage = false }) }
            }
            Group {
                row { ListRow("Insert Excel file", "Upload your mess menu (.xlsx)", Icons.Outlined.UploadFile, onClick = { manage = false; pick() }) }
                if (source != null) row { ListRow("View original sheet", null, Icons.Outlined.TableChart, onClick = { manage = false; nav.push { MessSheetPage() } }) }
                if (source != null || menu != null) row {
                    ListRow("Delete menu", "Remove the uploaded menu from this device", Icons.Outlined.Delete, titleColor = LocalStatus.current.danger, chevron = false, onClick = { confirm = true })
                }
            }
        }
        if (confirm) ConfirmDialog("Delete the mess menu?", { confirm = false }, "Delete") { Mess.clear(); manage = false }
    }
}

@Composable
private fun MessDay(menu: MessMenu, sheet: String?) {
    val today = LocalDate.now()
    val inMonth = today.year == menu.year && today.monthValue == menu.month
    val month = YearMonth.of(menu.year, menu.month)
    var day by remember(menu) { mutableIntStateOf(if (inMonth) today.dayOfMonth else (menu.daysFor(sheet).keys.minOrNull() ?: 1)) }
    var meal by remember { mutableIntStateOf(if (inMonth) currentMeal() else 0) }
    val date = month.atDay(day.coerceIn(1, month.lengthOfMonth()))
    val dayMenu = menu.menuFor(day, sheet)
    Column {
        // "‹  16 Nov · Mon  ›"
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(Lucide.ChevronLeft, "Previous day", Modifier.size(44.dp).clip(CircleShape)
                .clickable(enabled = day > 1) { day-- }.padding(10.dp), tint = if (day > 1) MaterialTheme.colorScheme.onSurface else LocalTones.current.tertiaryLabel)
            Text(date.format(DateTimeFormatter.ofPattern("d MMM · EEE", Locale.ENGLISH)),
                style = MaterialTheme.typography.titleLarge.copy(color = MaterialTheme.colorScheme.onSurface), modifier = Modifier.padding(horizontal = 18.dp))
            Icon(Lucide.ChevronRight, "Next day", Modifier.size(44.dp).clip(CircleShape)
                .clickable(enabled = day < month.lengthOfMonth()) { day++ }.padding(10.dp),
                tint = if (day < month.lengthOfMonth()) MaterialTheme.colorScheme.onSurface else LocalTones.current.tertiaryLabel)
        }
        Spacer(Modifier.height(12.dp))
        Segmented(MEALS, meal) { meal = it }
        Spacer(Modifier.height(8.dp))
        AnimatedContent(dayMenu?.forMeal(meal) to day, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "meal") { (items, _) ->
            if (items.isNullOrEmpty()) {
                Detail(if (dayMenu == null) "No menu for this day" else "No ${MEALS[meal].lowercase()} listed", Modifier.padding(vertical = 18.dp, horizontal = 4.dp))
            } else {
                Column {
                    items.forEachIndexed { i, item ->
                        if (i > 0) com.minimallabs.vsync.ui.Hairline()
                        Text(item, style = MaterialTheme.typography.bodyLarge.copy(fontSize = androidx.compose.ui.unit.TextUnit(17f, androidx.compose.ui.unit.TextUnitType.Sp)),
                            modifier = Modifier.padding(vertical = 15.dp))
                    }
                }
            }
        }
        val left = today.lengthOfMonth() - today.dayOfMonth
        if (inMonth && left <= 2) {
            Spacer(Modifier.height(14.dp))
            Detail(if (left == 0) "This is the last day of this menu. Upload next month's menu from your email when it arrives."
            else "This menu ends in $left ${if (left == 1) "day" else "days"}. Upload next month's menu from your email when it arrives.")
        }
    }
}

/** A workbook that does not read as a menu, shown as its first sheet. */
@Composable
fun MessSheetPage() {
    val grid = remember { Mess.rawGrid() }
    Page("Mess sheet", padding = 0.dp) {
        if (grid.isEmpty()) { Detail("The file is empty or could not be opened.", Modifier.padding(16.dp)); return@Page }
        Column(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            for ((r, row) in grid.withIndex()) Row {
                for (cell in row) Text(
                    cell, style = if (r == 0) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
                    modifier = Modifier.widthIn(min = 90.dp, max = 180.dp).border(0.5.dp, LocalTones.current.separator, RoundedCornerShape(0.dp)).padding(8.dp),
                )
            }
        }
    }
}
