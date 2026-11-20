package com.minimallabs.vsync.feature.account

import com.minimallabs.vsync.ui.Lucide
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AutoAwesomeMosaic
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.minimallabs.vsync.BuildConfig
import com.minimallabs.vsync.core.App
import com.minimallabs.vsync.core.AppIcons
import com.minimallabs.vsync.core.DemoMode
import com.minimallabs.vsync.core.Failures
import com.minimallabs.vsync.core.PRIVACY_POLICY_URL
import com.minimallabs.vsync.core.Semesters
import com.minimallabs.vsync.data.SemesterInfo
import com.minimallabs.vsync.feature.academics.timeAgo
import com.minimallabs.vsync.feature.auth.AuthRepo
import com.minimallabs.vsync.feature.auth.GmailOtpAccount
import com.minimallabs.vsync.feature.auth.GmailOtpReader
import com.minimallabs.vsync.feature.auth.GmailOtpStore
import com.minimallabs.vsync.feature.auth.ImapOtpMailbox
import com.minimallabs.vsync.feature.auth.MailboxSummary
import com.minimallabs.vsync.feature.auth.OtpReaderException
import com.minimallabs.vsync.feature.auth.SemesterYearList
import com.minimallabs.vsync.feature.calendar.CalendarRepo
import com.minimallabs.vsync.feature.dock.DockSettings
import com.minimallabs.vsync.feature.dock.DockShortcuts
import com.minimallabs.vsync.feature.outing.ConfirmDialog
import com.minimallabs.vsync.feature.widget.ClassWidget
import com.minimallabs.vsync.ui.AccentThemes
import com.minimallabs.vsync.ui.AppCard
import com.minimallabs.vsync.ui.Detail
import com.minimallabs.vsync.ui.ErrorLine
import com.minimallabs.vsync.ui.Group
import com.minimallabs.vsync.ui.KeyValue
import com.minimallabs.vsync.ui.ListRow
import com.minimallabs.vsync.ui.LocalNav
import com.minimallabs.vsync.ui.LocalStatus
import com.minimallabs.vsync.ui.LocalTones
import com.minimallabs.vsync.ui.Logo
import com.minimallabs.vsync.ui.Page
import com.minimallabs.vsync.ui.PrimaryButton
import com.minimallabs.vsync.ui.Segmented
import com.minimallabs.vsync.ui.TabScreen
import com.minimallabs.vsync.ui.glassSupported
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

const val WEBSITE_URL = "https://v-sync-minimallabs.vercel.app"

fun openUrl(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Full sync: all student data, then the calendar. Records `lastSync`. */
object SyncAll {
    var running by mutableStateOf(false)
        private set

    suspend fun run(): String? {
        if (running || DemoMode.isOn) return null
        val c = App.credentials ?: return "Your saved login could not be read. Please log out and log in again."
        running = true
        return try {
            val fresh = AuthRepo.fetchAll(c.registrationNumber, c.password, c.semSubId, maxRetries = 3)
            App.updateUser(fresh)
            App.updatePrefs { it.copy(lastSync = System.currentTimeMillis()) }
            runCatching { CalendarRepo.refresh() }
            ClassWidget.refresh(App.context)
            null
        } catch (e: Throwable) {
            Failures.messageFor(e)
        } finally {
            running = false
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AccountTab() {
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profile = App.user?.profile
    var confirmLogout by remember { mutableStateOf(false) }
    var pickSemester by remember { mutableStateOf(false) }

    Page("", back = false) {
        // The Flutter header: the name, big, in the heading colour; the semester as a link under it.
        val name = profile?.studentName?.ifBlank { null } ?: App.credentials?.registrationNumber ?: "Student"
        Text(name, style = MaterialTheme.typography.displaySmall.copy(fontSize = 38.sp, lineHeight = 44.sp),
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { nav.push { ProfilePage() } })
        Row(
            Modifier.offset(y = (-4).dp).clip(RoundedCornerShape(8.dp)).clickable { pickSemester = true }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(Semesters.selected()?.name ?: App.credentials?.semSubId ?: "Select semester", style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp),
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Icon(Lucide.Pencil, "Change semester", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(8.dp))
        Group {
            row { ListRow("Manage credentials", null, Lucide.KeyRound, onClick = { nav.push { ManageCredentialsPage() } }) }
            row { ListRow("Appearance", null, Lucide.Moon, onClick = { nav.push { AppearancePage() } }) }
            row { ListRow("Customization", null, Lucide.Palette, onClick = { nav.push { CustomizationPage() } }) }
            row {
                ListRow("Refresh all data", if (SyncAll.running) "Syncing with VTOP..." else null, Lucide.RefreshCw, chevron = false, onClick = {
                    scope.launch {
                        App.toast("Syncing with VTOP in the background...")
                        App.toast(SyncAll.run() ?: "Successfully synced with VTOP")
                    }
                })
            }
            row {
                val gmail = GmailOtpStore.read()
                ListRow("Automatic OTP Fetching", if (gmail != null && GmailOtpStore.isEnabled()) "On · ${gmail.email}" else "Read your VTOP code from Gmail",
                    Lucide.MailCheck, onClick = { nav.push { OtpAutofetchPage() } })
            }
            row { ListRow("Thumb Dock", if (DockSettings.enabled) "On" else null, Lucide.Hand, onClick = { nav.push { ThumbDockPage() } }) }
            row { ListRow("Website", null, Lucide.Globe, onClick = { openUrl(context, WEBSITE_URL) }) }
            row { ListRow("Privacy Policy", null, Lucide.ShieldCheck, onClick = { openUrl(context, PRIVACY_POLICY_URL) }) }
            row { ListRow("About", null, Lucide.Info, onClick = { nav.push { AboutPage() } }) }
            row { ListRow("Logout", null, Lucide.LogOut, titleColor = LocalStatus.current.danger, chevron = false, onClick = { confirmLogout = true }) }
        }
    }

    if (confirmLogout) ConfirmDialog("Log out?", { confirmLogout = false }, "Log out", "Your saved data on this phone is removed.") { App.logout() }

    if (pickSemester) {
        val semesters = remember { Semesters.all() }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        ModalBottomSheet(onDismissRequest = { if (!busy) pickSemester = false }, containerColor = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Semester", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp))
                error?.let { ErrorLine(it) }
                if (busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                if (semesters.isEmpty()) Detail("No semesters saved. Try again after the next sync.")
                else SemesterYearList(semesters, Semesters.selected()?.id) { s: SemesterInfo ->
                    if (busy) return@SemesterYearList
                    val c = App.credentials ?: return@SemesterYearList
                    busy = true; error = null
                    scope.launch {
                        try {
                            val fresh = if (DemoMode.isOn) App.user!! else AuthRepo.fetchAll(c.registrationNumber, c.password, s.id)
                            Semesters.select(s.id)
                            App.updateCredentials(c.copy(semSubId = s.id))
                            App.updateUser(fresh)
                            App.updatePrefs { it.copy(lastSync = System.currentTimeMillis()) }
                            CalendarRepo.reset()
                            runCatching { CalendarRepo.ensureLoaded() }
                            pickSemester = false
                        } catch (e: Throwable) {
                            error = Failures.messageFor(e)
                        } finally {
                            busy = false
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfilePage() {
    val p = App.user?.profile
    Page("Profile") {
        Group("You") {
            row { Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Column {
                    KeyValue("Name", p?.studentName ?: "")
                    KeyValue("Registration", p?.registrationNumber ?: "")
                    KeyValue("Application no.", p?.applicationNumber ?: "")
                    KeyValue("Date of birth", p?.dob ?: "")
                    KeyValue("Blood group", p?.bloodGroup ?: "")
                    KeyValue("Email", p?.email ?: "")
                }
            } }
        }
        val m = p?.mentorDetails
        if (m != null && m.facultyName.isNotBlank()) Group("Mentor") {
            row { Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Column {
                    KeyValue("Name", m.facultyName)
                    KeyValue("Designation", m.facultyDesignation)
                    KeyValue("Department", m.facultyDepartment)
                    KeyValue("Cabin", m.cabin)
                    KeyValue("Email", m.facultyEmail)
                    KeyValue("Phone", m.facultyMobileNumber)
                    KeyValue("Intercom", m.facultyIntercom)
                }
            } }
        }
    }
}

@Composable
fun AppearancePage() {
    val p = App.prefs
    val context = LocalContext.current
    var icon by remember { mutableStateOf(runCatching { AppIcons.current(context) }.getOrDefault("default")) }
    val mode = when { p.followSystemTheme -> 2; !p.hasUserChosenTheme || p.isDarkModeEnabled -> 0; else -> 1 }
    Page("Appearance") {
        Segmented(listOf("Dark", "Light", "System"), mode) { i ->
            App.updatePrefs {
                when (i) {
                    0 -> it.copy(followSystemTheme = false, hasUserChosenTheme = true, isDarkModeEnabled = true)
                    1 -> it.copy(followSystemTheme = false, hasUserChosenTheme = true, isDarkModeEnabled = false)
                    else -> it.copy(followSystemTheme = true)
                }
            }
        }
        Group(footer = if (!glassSupported) "Liquid glass needs Android 12 or newer." else "Glass bends and blurs what is behind it. Turn it off on slower phones.") {
            row { ListRow("Liquid Glass", null, Icons.Outlined.BlurOn, checked = p.liquidGlass && glassSupported, onCheckedChange = { on -> App.updatePrefs { it.copy(liquidGlass = on) } }) }
            row { ListRow("Pure black", "In dark mode", Icons.Outlined.DarkMode, checked = p.isAmoledEnabled, onCheckedChange = { on -> App.updatePrefs { it.copy(isAmoledEnabled = on) } }) }
        }
        Group("Accent") {
            for ((key, name) in AccentThemes) row {
                ListRow(name, null, chevron = false, value = if (p.appTheme == key) "✓" else null, onClick = { App.updatePrefs { it.copy(appTheme = key) } })
            }
        }
        Group("Text size") {
            row {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("A", style = MaterialTheme.typography.bodySmall)
                    Slider(value = p.fontScale.toFloat(), onValueChange = { v -> App.updatePrefs { it.copy(fontScale = Math.round(v * 20) / 20.0) } },
                        valueRange = 0.9f..1.5f, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                    Text("A", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        Group("App icon") {
            row {
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (v in AppIcons.all) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable {
                        runCatching { AppIcons.set(context, v.key); icon = v.key; App.toast("App icon updated. It may take a few seconds to show up.") }
                            .onFailure { App.toast("Could not change the app icon") }
                    }.padding(6.dp)) {
                        Image(painterResource(v.preview), v.label, Modifier.size(58.dp).clip(RoundedCornerShape(15.dp)).background(androidx.compose.ui.graphics.Color(0xFF16181C))
                            .let { m -> if (icon == v.key) m.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(15.dp)) else m })
                        Spacer(Modifier.height(6.dp))
                        Detail(v.label)
                    }
                }
            }
        }
    }
}

@Composable
fun CustomizationPage() {
    val p = App.prefs
    Page("Customization") {
        Group("Academics", footer = "Grid shows the pages two per row.") {
            row { ListRow("Grid layout", null, Icons.Outlined.AutoAwesomeMosaic, checked = !p.academicsListView, onCheckedChange = { on -> App.updatePrefs { it.copy(academicsListView = !on) } }) }
        }
        Group("Pages shown") {
            row { ListRow("Grades", checked = !p.hideGrades, onCheckedChange = { on -> App.updatePrefs { it.copy(hideGrades = !on) } }) }
            row { ListRow("Biometric Log", checked = !p.hideBiometric, onCheckedChange = { on -> App.updatePrefs { it.copy(hideBiometric = !on) } }) }
            row { ListRow("Digital Assignments", checked = !p.hideDigitalAssignments, onCheckedChange = { on -> App.updatePrefs { it.copy(hideDigitalAssignments = !on) } }) }
            row { ListRow("Outing", checked = !p.hideOuting, onCheckedChange = { on -> App.updatePrefs { it.copy(hideOuting = !on) } }) }
            row { ListRow("Faculty Info", checked = !p.hideFacultyInfo, onCheckedChange = { on -> App.updatePrefs { it.copy(hideFacultyInfo = !on) } }) }
            row { ListRow("Open VTOP", checked = !p.hideOpenVtop, onCheckedChange = { on -> App.updatePrefs { it.copy(hideOpenVtop = !on) } }) }
            row { ListRow("Academic Calendar", checked = !p.hideAcademicCalendar, onCheckedChange = { on -> App.updatePrefs { it.copy(hideAcademicCalendar = !on) } }) }
        }
        Group("Home") {
            row { ListRow("Class card", "Current and next class on For You", Icons.Outlined.ViewAgenda, checked = p.classStackEnabled, onCheckedChange = { on -> App.updatePrefs { it.copy(classStackEnabled = on) } }) }
        }
        Group("Outing", footer = "Lets the app submit a weekend outing after the usual Friday/Saturday deadline. VTOP may still refuse it.") {
            row { ListRow("Ignore weekend deadline", null, Icons.Outlined.FlightTakeoff, checked = p.bypassWeekendOutingRestriction, onCheckedChange = { on -> App.updatePrefs { it.copy(bypassWeekendOutingRestriction = on) } }) }
        }
    }
}

@Composable
fun ThumbDockPage() {
    Page("Thumb Dock") {
        Group(footer = "A handle on the edge of the screen. Hold it and slide to any page, or tap it. Drag the handle to move it to either edge.") {
            row { ListRow("Thumb Dock", null, Icons.Outlined.PanTool, checked = DockSettings.enabled, onCheckedChange = { DockSettings.turn(it) }) }
        }
        Group("Academics shortcuts", footer = "Attendance and Marks always stay. Choose up to ${DockSettings.MAX - DockSettings.locked.size} more.") {
            for (s in DockShortcuts.all) row {
                val locked = s.id in DockSettings.locked
                ListRow(s.label, if (locked) "Always shown" else null, s.icon, checked = s.id in DockSettings.shortcuts,
                    onCheckedChange = if (locked) null else ({ _ -> DockSettings.toggle(s.id) }))
            }
        }
    }
}

@Composable
private fun AboutPage() {
    val context = LocalContext.current
    Page("About") {
        Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Logo(64.dp)
            Spacer(Modifier.height(12.dp))
            Text("V-Sync", style = MaterialTheme.typography.headlineMedium)
            Detail("Version ${BuildConfig.VERSION_NAME}")
        }
        Group(footer = "V-Sync is open source. It is not affiliated with VIT-AP University.") {
            row { ListRow("Website", null, Lucide.Globe, onClick = { openUrl(context, WEBSITE_URL) }) }
            row { ListRow("Privacy Policy", null, Lucide.ShieldCheck, onClick = { openUrl(context, PRIVACY_POLICY_URL) }) }
        }
        Group("Built on the work of", footer = "V-Sync began as a fork of their VIT-AP Student app; the VTOP scraper is theirs (Apache-2.0).") {
            row { ListRow("Udhay Adithya", "Mobile application developer", Icons.Outlined.Code, onClick = { openUrl(context, "https://github.com/Udhay-Adithya") }) }
            row { ListRow("Sai Sanjay", "Backend / DevOps engineer", Icons.Outlined.Code, onClick = { openUrl(context, "https://github.com/sanjay7178") }) }
        }
    }
}

@Composable
fun ManageCredentialsPage() {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val c = App.credentials
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Page("Credentials") {
        if (c == null) { ErrorLine("Credentials not found"); return@Page }
        Group(footer = "Changed your VTOP password? Enter the new one. It is checked with VTOP before it is saved.") {
            row { ListRow("Registration", value = c.registrationNumber, chevron = false) }
        }
        OutlinedTextField(password, { password = it }, label = { Text("New password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        error?.let { ErrorLine(it) }
        PrimaryButton("Save", Modifier.fillMaxWidth(), enabled = password.isNotEmpty() && !DemoMode.isOn, busy = busy) {
            busy = true; error = null
            scope.launch {
                try {
                    App.session.resetClient()
                    AuthRepo.fetchSemesters(c.registrationNumber, password) // proves the password works
                    App.updateCredentials(c.copy(password = password))
                    App.toast("Credentials updated successfully")
                    nav.pop()
                } catch (e: Throwable) {
                    error = Failures.messageFor(e)
                } finally {
                    busy = false
                }
            }
        }
    }
}

// ------------------------------------------------------------------ automatic OTP

private fun seenIn(s: MailboxSummary): String {
    if (s.count == 0) return "No email from VTOP in your Inbox in the last 30 days. The next code will be picked up when it arrives."
    val count = "${s.count} VTOP email${if (s.count == 1) "" else "s"} in your Inbox in the last 30 days"
    val latest = s.latest ?: return "Found $count."
    val at = Instant.ofEpochMilli(latest).atZone(ZoneId.systemDefault())
    return "Found $count. The newest arrived ${at.format(DateTimeFormatter.ofPattern("d MMM, h:mm a", Locale.ENGLISH))}."
}

/**
 * Gmail OTP reader set-up. The disclosure is deliberately plain (Play review):
 * the fields only appear after the student agrees, and agreement is asked afresh each time.
 */
@Composable
fun OtpAutofetchPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf(GmailOtpStore.read()) }
    var enabled by remember { mutableStateOf(GmailOtpStore.isEnabled()) }
    var agreed by remember { mutableStateOf(false) }
    var showConsent by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var appPassword by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // message, isError
    var confirmRemove by remember { mutableStateOf(false) }

    fun check(acc: GmailOtpAccount, onOk: () -> Unit) {
        busy = true; status = null
        scope.launch {
            try {
                val seen = GmailOtpReader({ ImapOtpMailbox(acc.email, acc.appPassword) }).check()
                val wasNew = account == null
                onOk()
                status = (if (wasNew) "Connected. " else "Gmail works. ") + seenIn(seen) to false
            } catch (e: OtpReaderException) {
                status = "Could not connect to Gmail. ${e.userMessage}" to true
            } catch (_: Throwable) {
                status = "Could not connect to Gmail." to true
            } finally {
                busy = false
            }
        }
    }

    Page("Automatic OTP", subtitle = "VSync can read the code VTOP emails you and enter it for you, on this phone, through your own Gmail.") {
        val acc = account
        if (acc != null) {
            Group(footer = acc.email) {
                row { ListRow("Connected", null, Icons.Outlined.MarkEmailRead, titleColor = LocalStatus.current.success, chevron = false) }
                row { ListRow("Read codes automatically", checked = enabled, onCheckedChange = { on -> GmailOtpStore.setEnabled(on); enabled = on }) }
                row { ListRow(if (busy) "Testing..." else "Test connection", chevron = false, onClick = { if (!busy) check(acc) {} }) }
                row { ListRow("Remove Gmail", titleColor = LocalStatus.current.danger, chevron = false, onClick = { confirmRemove = true }) }
            }
        } else if (!agreed) {
            AppCard {
                Text("Not set up", style = MaterialTheme.typography.titleMedium)
                Detail("This is optional. It needs a Google app password, and VSync will ask you to review exactly what it can see before you enter anything.")
                Spacer(Modifier.height(14.dp))
                PrimaryButton("Review and set up", Modifier.fillMaxWidth()) { showConsent = true }
            }
        } else {
            Group("Set up, once", footer = "If Google says app passwords are not available for your account, your college has turned them off and this cannot be used.") {
                row { ListRow("1. Turn on 2-Step Verification", "For the Google account that gets your VTOP emails", onClick = { openUrl(context, "https://myaccount.google.com/security") }) }
                row { ListRow("2. Create an app password", "Name it VSync", onClick = { openUrl(context, "https://myaccount.google.com/apppasswords") }) }
                row { ListRow("3. Paste both below", null, chevron = false) }
            }
            OutlinedTextField(email, { email = it.trim() }, label = { Text("Gmail address") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(appPassword, { appPassword = it }, label = { Text("App password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Detail("An app password is 16 letters, shown by Google in four groups of four.", Modifier.padding(horizontal = 4.dp))
            PrimaryButton("Connect", Modifier.fillMaxWidth(), busy = busy) {
                val pw = GmailOtpStore.normalisePassword(appPassword)
                when {
                    !GmailOtpStore.isValidEmail(email) -> status = "Enter the Gmail address your VTOP codes are sent to." to true
                    pw == null -> status = "An app password is 16 letters, shown by Google in four groups of four." to true
                    else -> {
                        val a = GmailOtpAccount(email, pw)
                        check(a) { GmailOtpStore.save(a); account = a; enabled = true; appPassword = "" }
                    }
                }
            }
        }
        status?.let { (msg, isError) -> if (isError) ErrorLine(msg) else Detail(msg, Modifier.padding(horizontal = 4.dp)) }
    }

    if (showConsent) AlertDialog(
        onDismissRequest = {}, // must answer
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("Read your VTOP code from Gmail?", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("To do this, VSync signs in to your Gmail account on this phone with an app password that you create, and reads the login code that VTOP emails you.", style = MaterialTheme.typography.bodyMedium)
                Text("It only looks at email from noreply.sdc@vitap.ac.in, and only while VSync is waiting for a login code.", style = MaterialTheme.typography.bodyMedium)
                Text("It only reads. It never changes, marks, moves, deletes or sends any email.", style = MaterialTheme.typography.bodyMedium)
                Text("Your Gmail address and app password are stored encrypted on this phone (Android Keystore). They are used only to sign in to Gmail, and are never sent to VSync or to anyone else.", style = MaterialTheme.typography.bodyMedium)
                Text("Nothing from your mailbox leaves this phone, except the code VSync enters into VTOP for you.", style = MaterialTheme.typography.bodyMedium)
                Text("You can remove it here at any time, and revoke the app password in your Google account.", style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = { showConsent = false; agreed = true }) { Text("I agree") } },
        dismissButton = { TextButton(onClick = { showConsent = false }) { Text("Not now") } },
    )

    if (confirmRemove) ConfirmDialog("Remove Gmail?", { confirmRemove = false }, "Remove",
        "VSync forgets the app password and stops reading your mail. You can set it up again later.") {
        GmailOtpStore.clear(); account = null; enabled = false; agreed = false
        status = "Removed. VSync no longer reads your Gmail." to false
    }
}

