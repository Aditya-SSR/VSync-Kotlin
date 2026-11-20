package com.minimallabs.vsync.feature.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minimallabs.vsync.core.App
import com.minimallabs.vsync.core.DemoMode
import com.minimallabs.vsync.core.Failures
import com.minimallabs.vsync.core.PRIVACY_POLICY_URL
import com.minimallabs.vsync.core.Semesters
import com.minimallabs.vsync.core.UserMessage
import com.minimallabs.vsync.data.AppJson
import com.minimallabs.vsync.data.Credentials
import com.minimallabs.vsync.data.SemesterData
import com.minimallabs.vsync.data.SemesterInfo
import com.minimallabs.vsync.data.UserData
import com.minimallabs.vsync.feature.account.openUrl
import com.minimallabs.vsync.feature.calendar.CalendarRepo
import com.minimallabs.vsync.ui.Detail
import com.minimallabs.vsync.ui.ErrorLine
import com.minimallabs.vsync.ui.GlassKind
import com.minimallabs.vsync.ui.GlassRoot
import com.minimallabs.vsync.ui.GroupShape
import com.minimallabs.vsync.ui.Hairline
import com.minimallabs.vsync.ui.Inter
import com.minimallabs.vsync.ui.LocalStatus
import com.minimallabs.vsync.ui.LocalTones
import com.minimallabs.vsync.ui.Logo
import com.minimallabs.vsync.ui.PrimaryButton
import com.minimallabs.vsync.ui.SecondaryButton
import com.minimallabs.vsync.ui.TextAction
import com.minimallabs.vsync.ui.glass
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ data calls

object AuthRepo {
    suspend fun fetchSemesters(username: String, password: String): List<SemesterInfo> {
        val text = App.session.execute(username, password) { it.text("semesters") }
        return AppJson.decodeFromString<SemesterData>(text).semesters
    }

    suspend fun fetchAll(username: String, password: String, semSubId: String, maxRetries: Int = 2): UserData {
        if (semSubId.isEmpty()) throw UserMessage("Semester ID is missing. Please logout and login again.")
        val text = App.session.execute(username, password, maxRetries) { it.text("all_data", semSubId) }
        return try {
            AppJson.decodeFromString<UserData>(text)
        } catch (_: Exception) {
            throw UserMessage("Invalid response format from server")
        }
    }
}

// ------------------------------------------------------------------ semester grouping

class SemesterEntry(val info: SemesterInfo, val shortName: String)
class SemesterYear(val label: String, val entries: List<SemesterEntry>) {
    fun contains(id: String?) = id != null && entries.any { it.info.id == id }
}

/** Groups "Fall Semester 2025-26" under "2025-26", newest year first. */
object SemesterGrouping {
    const val OTHER = "Other"
    private val span = Regex("(\\d{4})\\s*[-–/]\\s*(\\d{2,4})")
    private val single = Regex("\\b(20\\d{2})\\b")

    private class Year(val label: String, val start: Int, val token: String)

    fun group(semesters: List<SemesterInfo>): List<SemesterYear> {
        val byLabel = LinkedHashMap<String, MutableList<SemesterEntry>>()
        val startOf = HashMap<String, Int>()
        for (s in semesters) {
            val parsed = yearOf(s.name)
            val label = parsed?.label ?: OTHER
            startOf[label] = parsed?.start ?: -1
            byLabel.getOrPut(label) { ArrayList() }.add(SemesterEntry(s, withoutYear(s.name, parsed?.token)))
        }
        val labels = byLabel.keys.sortedWith { a, b ->
            when {
                a == b -> 0
                a == OTHER -> 1
                b == OTHER -> -1
                else -> startOf[b]!!.compareTo(startOf[a]!!)
            }
        }
        return labels.map { SemesterYear(it, byLabel[it]!!) }
    }

    private fun yearOf(name: String): Year? {
        span.find(name)?.let { m ->
            val start = m.groupValues[1].toInt()
            val end = m.groupValues[2]
            return Year("$start-${if (end.length == 4) end.substring(2) else end}", start, m.value)
        }
        single.find(name)?.let { m -> return Year(m.groupValues[1], m.groupValues[1].toInt(), m.value) }
        return null
    }

    private fun withoutYear(name: String, token: String?): String {
        if (token == null) return name.trim()
        val tidy = name.replaceFirst(token, " ")
            .replace(Regex("\\s+"), " ")
            .replace(Regex("^[\\s\\-–,]+|[\\s\\-–,]+$"), "")
            .replace(Regex("\\s+[-–]\\s+[-–]\\s+"), " - ")
            .trim()
        return tidy.ifEmpty { name.trim() }
    }
}

/** Collapsible year groups; the year holding [selectedId] (or the newest) starts open. */
@Composable
fun SemesterYearList(semesters: List<SemesterInfo>, selectedId: String?, onPick: (SemesterInfo) -> Unit) {
    val years = remember(semesters) { SemesterGrouping.group(semesters) }
    var open by remember(years) { mutableStateOf(years.firstOrNull { it.contains(selectedId) }?.label ?: years.firstOrNull()?.label) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (year in years) {
            Column(Modifier.fillMaxWidth().glass(GroupShape).clip(GroupShape)) {
                Row(
                    Modifier.fillMaxWidth().clickable { open = if (open == year.label) null else year.label }.padding(horizontal = 18.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(year.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Icon(if (open == year.label) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, null, tint = LocalTones.current.secondaryLabel)
                }
                AnimatedVisibility(open == year.label) {
                    Column {
                        for (e in year.entries) {
                            Hairline(Modifier.padding(start = 18.dp))
                            Row(
                                Modifier.fillMaxWidth().clickable { onPick(e.info) }.padding(horizontal = 18.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(e.shortName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                if (e.info.id == selectedId) Icon(Icons.Rounded.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ fields

/** An iOS-style inset field: a label on the left, the input on the right. */
@Composable
private fun InsetField(
    label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier,
    keyboard: KeyboardOptions = KeyboardOptions.Default, actions: KeyboardActions = KeyboardActions.Default,
    secret: Boolean = false, enabled: Boolean = true, placeholder: String = "", trailing: (@Composable () -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp).height(54.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(104.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = LocalTones.current.tertiaryLabel)
            BasicTextField(
                value, onChange, enabled = enabled, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = keyboard, keyboardActions = actions, modifier = Modifier.fillMaxWidth(),
            )
        }
        trailing?.invoke()
    }
}

// ------------------------------------------------------------------ login flow

@Composable
fun LoginScreen() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var show by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var semesters by remember { mutableStateOf<List<SemesterInfo>?>(null) }
    var status by remember { mutableStateOf("") }

    fun finishLogin(sem: SemesterInfo) {
        busy = true; error = null; status = "Getting your data from VTOP..."
        scope.launch {
            try {
                val reg = username.trim().uppercase()
                val user = AuthRepo.fetchAll(reg, password, sem.id)
                Semesters.select(sem.id)
                App.updatePrefs { it.copy(lastSync = System.currentTimeMillis()) }
                App.login(user, Credentials(reg, password, sem.id))
                App.scope.launch { runCatching { CalendarRepo.ensureLoaded() } }
            } catch (e: Throwable) {
                error = Failures.messageFor(e)
            } finally {
                busy = false
            }
        }
    }

    fun submit() {
        if (busy) return
        val reg = username.trim().uppercase()
        if (reg.isEmpty() || password.isEmpty()) { error = "Enter your registration number and password."; return }
        error = null
        if (DemoMode.isDemoCredentials(reg, password)) {
            runCatching {
                val user = DemoMode.user()
                DemoMode.set(true)
                App.login(user, DemoMode.credentials)
            }.onFailure { DemoMode.set(false); error = "Failed to start the demo. Please try again." }
            return
        }
        busy = true; status = "Signing in to VTOP..."
        scope.launch {
            try {
                val list = AuthRepo.fetchSemesters(reg, password)
                Semesters.save(list)
                semesters = list
            } catch (e: Throwable) {
                error = Failures.messageFor(e)
            } finally {
                busy = false
            }
        }
    }

    GlassRoot {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val list = semesters
            Crossfade(list == null, label = "login") { credentialsStep ->
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (credentialsStep) {
                        Spacer(Modifier.height(56.dp))
                        Box(Modifier.size(76.dp).glass(RoundedCornerShape(24.dp), GlassKind.Control), contentAlignment = Alignment.Center) { Logo(38.dp) }
                        Spacer(Modifier.height(6.dp))
                        Text("Sign in", style = MaterialTheme.typography.displaySmall)
                        Detail("Use your VTOP registration number and password.")
                        Spacer(Modifier.height(6.dp))
                        // One autofill context: password managers fill both together.
                        Column(Modifier.fillMaxWidth().glass(GroupShape).clip(GroupShape)) {
                            InsetField(
                                "Reg. no.", username, { username = it }, enabled = !busy, placeholder = "25BCE1234",
                                modifier = Modifier.semantics { contentType = ContentType.Username },
                                keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Next),
                            )
                            Hairline(Modifier.padding(start = 18.dp))
                            InsetField(
                                "Password", password, { password = it }, enabled = !busy, secret = !show, placeholder = "Required",
                                modifier = Modifier.semantics { contentType = ContentType.Password },
                                keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                                actions = KeyboardActions(onDone = { submit() }),
                                trailing = {
                                    Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Show password",
                                        Modifier.size(40.dp).clip(CircleShape).clickable { show = !show }.padding(9.dp), tint = LocalTones.current.secondaryLabel)
                                },
                            )
                        }
                        error?.let { ErrorLine(it) }
                        Spacer(Modifier.height(4.dp))
                        PrimaryButton("Continue", Modifier.fillMaxWidth(), busy = busy) { submit() }
                        if (busy) Detail(status, Modifier.align(Alignment.CenterHorizontally))
                        Spacer(Modifier.height(8.dp))
                        TextAction("Terms & privacy policy", Modifier.align(Alignment.CenterHorizontally), color = LocalTones.current.secondaryLabel) {
                            openUrl(context, PRIVACY_POLICY_URL)
                        }
                    } else {
                        Spacer(Modifier.height(44.dp))
                        Text("Your semester", style = MaterialTheme.typography.displaySmall)
                        Detail("You can change this later in Account.")
                        error?.let { ErrorLine(it) }
                        if (busy) {
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape), trackColor = LocalTones.current.fill)
                            Detail(status)
                        } else {
                            SemesterYearList(list ?: emptyList(), null) { finishLogin(it) }
                            TextAction("Use a different account", Modifier.align(Alignment.CenterHorizontally)) { semesters = null; App.session.resetClient() }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ------------------------------------------------------------------ OTP prompt (mounted above everything)

/** Six boxes for the code; a hidden field takes the input (and the SMS/autofill suggestion). */
@Composable
private fun CodeBoxes(code: String, enabled: Boolean, onChange: (String) -> Unit, onDone: () -> Unit) {
    BasicTextField(
        code, { v -> onChange(v.filter { it.isDigit() }.take(6)) }, enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        textStyle = TextStyle(color = Color.Transparent),
        cursorBrush = SolidColor(Color.Transparent),
        modifier = Modifier.semantics { contentType = ContentType.SmsOtpCode },
        decorationBox = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in 0 until 6) {
                    val ch = code.getOrNull(i)?.toString() ?: ""
                    val focus = i == code.length
                    Box(
                        Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(14.dp))
                            .background(LocalTones.current.fill)
                            .let { if (focus) it.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)) else it },
                        contentAlignment = Alignment.Center,
                    ) { Text(ch, style = MaterialTheme.typography.headlineSmall.copy(fontFamily = Inter, fontSize = 24.sp)) }
                }
            }
        },
    )
}

@Composable
fun OtpOverlay(dockActive: Boolean = false, bottomInset: androidx.compose.ui.unit.Dp = 96.dp) {
    val c = OtpPrompt.state
    if (!c.isActive) return
    if (c.minimized) {
        // With the Thumb Dock on, the collapsed prompt lives on the dock handle instead.
        if (dockActive) return
        Box(Modifier.fillMaxSize().navigationBarsPadding().padding(end = 18.dp, bottom = bottomInset), contentAlignment = Alignment.BottomEnd) {
            Row(
                Modifier.glass(CircleShape, GlassKind.Bar).clip(CircleShape).clickable { OtpPrompt.reopen() }.padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.VpnKey, null, Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (c.remainingSeconds > 0) "OTP ${OtpChallenge.formatSeconds(c.remainingSeconds)}" else "Enter OTP", style = MaterialTheme.typography.labelLarge)
            }
        }
        return
    }
    var code by remember { mutableStateOf("") }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { OtpPrompt.minimize() }
            .imePadding().navigationBarsPadding().padding(12.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().glass(RoundedCornerShape(32.dp), GlassKind.Bar).clip(RoundedCornerShape(32.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 5.dp).clip(CircleShape).background(LocalTones.current.tertiaryLabel))
            if (c.isSucceeded) {
                Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(64.dp).clip(CircleShape).background(LocalStatus.current.success.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Check, null, Modifier.size(36.dp), tint = LocalStatus.current.success)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("OTP fetched successfully", style = MaterialTheme.typography.titleMedium)
                    Detail("Signing you in")
                }
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Verification", style = MaterialTheme.typography.headlineSmall)
                    Detail(c.message.ifBlank { "Enter the OTP sent to your registered email." })
                }
                Icon(Icons.Rounded.KeyboardArrowDown, "Minimise", Modifier.size(36.dp).clip(CircleShape).clickable { OtpPrompt.minimize() }.padding(6.dp))
            }
            CodeBoxes(code, !c.isBusy, { code = it; if (it.length == 6) OtpPrompt.submit(it) }) { OtpPrompt.submit(code) }
            when (c.autoPhase) {
                OtpAutoPhase.Fetching -> AutoRow("Fetching OTP automatically...")
                OtpAutoPhase.Verifying -> AutoRow("OTP fetched. Verifying...")
                OtpAutoPhase.Failed -> Detail(c.autoStatus ?: "Could not fetch the code automatically.")
                OtpAutoPhase.NotEnabled -> Detail("Automatic OTP fetching is not enabled.")
                else -> {}
            }
            c.errorMessage?.let { ErrorLine(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(if (c.remainingSeconds > 0) "Resend in ${c.remainingSeconds}s" else "Resend", Modifier.weight(1f), enabled = c.canResend) { OtpPrompt.resend() }
                PrimaryButton("Verify", Modifier.weight(1f), busy = c.activity == OtpActivity.Submitting) { OtpPrompt.submit(code) }
            }
            TextAction("Cancel sign-in", Modifier.align(Alignment.CenterHorizontally), color = LocalTones.current.secondaryLabel) { OtpPrompt.cancel() }
        }
    }
}

@Composable
private fun AutoRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Detail(text)
    }
}

/** Shown when VTOP rejects the saved credentials (wrong password, account locked). */
@Composable
fun AuthFailureDialog() {
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { App.session.onAuthFailure.collect { message = it.ifBlank { "Invalid username or password." } } }
    val m = message ?: return
    AlertDialog(
        onDismissRequest = { message = null },
        title = { Text("Sign-in failed", style = MaterialTheme.typography.titleMedium) },
        text = { Text(m, textAlign = TextAlign.Start, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

