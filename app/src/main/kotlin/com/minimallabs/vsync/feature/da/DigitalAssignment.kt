package com.minimallabs.vsync.feature.da

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.minimallabs.vsync.bridge.VtopError
import com.minimallabs.vsync.core.App
import com.minimallabs.vsync.core.DemoMode
import com.minimallabs.vsync.core.Failures
import com.minimallabs.vsync.core.Files
import com.minimallabs.vsync.data.AppJson
import com.minimallabs.vsync.data.AssignmentDetail
import com.minimallabs.vsync.data.DigitalAssignment
import com.minimallabs.vsync.ui.AppCard
import com.minimallabs.vsync.ui.CenterLoader
import com.minimallabs.vsync.ui.EmptyState
import com.minimallabs.vsync.ui.ErrorLine
import com.minimallabs.vsync.ui.LocalNav
import com.minimallabs.vsync.ui.LocalStatus
import com.minimallabs.vsync.ui.Muted
import com.minimallabs.vsync.ui.Page
import com.minimallabs.vsync.ui.Pill
import com.minimallabs.vsync.ui.rememberLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val MAX_UPLOAD_BYTES = 4 * 1024 * 1024
val ALLOWED_UPLOAD_EXTENSIONS = listOf("pdf", "doc", "docx", "xls", "xlsx")

@Composable
fun DigitalAssignmentPage() {
    val nav = LocalNav.current
    val loader = rememberLoader<List<DigitalAssignment>>()
    fun load() = loader.run {
        if (DemoMode.isOn) DemoMode.section("digital_assignments")
        else { val sem = App.semesterId; AppJson.decodeFromString(App.vtop { it.text("digital_assignments", sem) }) }
    }
    LaunchedEffect(Unit) { load() }
    Page("DA Upload") {
        val rows = loader.value
        when {
            rows == null && loader.error != null -> EmptyState("Couldn't load assignments", loader.error, "Retry") { load() }
            rows == null -> CenterLoader()
            rows.isEmpty() -> EmptyState("No digital assignments", "Nothing is listed for this semester.")
            else -> for (a in rows) AppCard(onClick = { nav.push { AssignmentDetailPage(a) { load() } } }) {
                Text(a.courseTitle, style = MaterialTheme.typography.titleSmall)
                Muted("${a.courseCode} · ${a.courseType} · ${a.faculty}")
                Muted("${a.details.size} assignment${if (a.details.size == 1) "" else "s"}")
            }
        }
    }
}

@Composable
private fun AssignmentDetailPage(assignment: DigitalAssignment, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var askOtp by remember { mutableStateOf(false) }
    var otpError by remember { mutableStateOf<String?>(null) }
    var pendingMode by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val mode = pendingMode
        if (uri == null || mode == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true; message = null
            try {
                val (name, bytes) = withContext(Dispatchers.IO) {
                    var n = "assignment"
                    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (i >= 0 && c.moveToFirst()) n = c.getString(i) ?: n
                    }
                    n to (context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0))
                }
                val ext = name.substringAfterLast('.', "").lowercase()
                when {
                    bytes.isEmpty() -> message = "Failed to read file"
                    bytes.size > MAX_UPLOAD_BYTES -> message = "File too large (%.1f MB). Maximum allowed size is 4 MB.".format(bytes.size / (1024.0 * 1024.0))
                    ext !in ALLOWED_UPLOAD_EXTENSIONS -> message = "Unsupported file type (.$ext). Allowed: ${ALLOWED_UPLOAD_EXTENSIONS.joinToString(", ") { ".$it" }}"
                    else -> try {
                        val reply = App.vtop { it.upload("upload_digital_assignment", bytes, assignment.classId, mode, name) }
                        App.toast(reply.ifBlank { "Assignment uploaded." })
                        onChanged()
                    } catch (e: VtopError) {
                        // Replacing an existing document needs an emailed OTP.
                        if (e.type == "DigitalAssignmentUploadOtpRequired") { otpError = null; askOtp = true } else message = Failures.messageFor(e)
                    }
                }
            } catch (e: Throwable) {
                message = Failures.messageFor(e, "Failed to read file")
            } finally {
                busy = false
            }
        }
    }

    fun download(url: String, label: String) {
        if (busy) return
        busy = true
        scope.launch {
            Files.fetchSaveOpen("${assignment.courseCode}_$label", "Digital Assignment") { App.vtop { it.bytes("download_digital_assignment", url) } }
            busy = false
        }
    }

    Page(assignment.courseCode) {
        Text(assignment.courseTitle, style = MaterialTheme.typography.titleMedium)
        Muted("${assignment.courseType} · ${assignment.faculty}")
        if (busy) CenterLoader()
        message?.let { ErrorLine(it) }
        if (assignment.details.isEmpty()) Muted("No assignments posted for this course.")
        for (d in assignment.details) AssignmentTile(d, busy,
            onUpload = { pendingMode = d.mcode; picker.launch("*/*") },
            onDownload = ::download)
    }

    if (askOtp) {
        var otp by remember { mutableStateOf("") }
        var submitting by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!submitting) askOtp = false },
            title = { Text("Verify upload") },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text("Any update to an existing document requires OTP authentication. Enter the 6-digit OTP sent to your registered email.")
                    OutlinedTextField(otp, { v -> otp = v.filter { it.isDigit() }.take(6) }, label = { Text("Enter OTP") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    otpError?.let { ErrorLine(it) }
                }
            },
            confirmButton = {
                TextButton(enabled = !submitting, onClick = {
                    if (otp.isBlank()) { otpError = "Please enter the OTP"; return@TextButton }
                    submitting = true
                    scope.launch {
                        try {
                            val reply = App.vtop { it.text("upload_digital_assignment_otp", otp) }
                            App.toast(reply.ifBlank { "Assignment uploaded." })
                            askOtp = false
                            onChanged()
                        } catch (e: Throwable) {
                            otpError = if (e is VtopError && e.type == "DigitalAssignmentUploadIncorrectOtp") "Incorrect OTP. Please try again." else Failures.messageFor(e)
                        } finally {
                            submitting = false
                        }
                    }
                }) { Text(if (submitting) "Verifying..." else "Submit") }
            },
            dismissButton = { TextButton(enabled = !submitting, onClick = { askOtp = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AssignmentTile(d: AssignmentDetail, busy: Boolean, onUpload: () -> Unit, onDownload: (String, String) -> Unit) {
    val status = LocalStatus.current
    val submitted = d.canDaDownload || d.submissionStatus.isNotBlank() && !d.submissionStatus.lowercase().contains("not")
    AppCard {
        Row {
            Text(d.assignmentTitle, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Pill(if (submitted) "Submitted" else if (d.canUpdate) "Pending" else "Missed Deadline",
                color = if (submitted) status.success else if (d.canUpdate) status.warning else status.danger)
        }
        Muted("Due: ${d.dueDate}")
        Muted("Max: ${d.maxAssignmentMark} · Weightage: ${d.assignmentWeightageMark}")
        if (d.submissionStatus.isNotBlank()) Muted(d.submissionStatus)
        Row(Modifier.fillMaxWidth()) {
            if (d.canUpdate) TextButton(enabled = !busy, onClick = onUpload) { Text(if (submitted) "Update" else "Upload") }
            if (d.canQpDownload && d.qpDownloadUrl.isNotEmpty()) TextButton(enabled = !busy, onClick = { onDownload(d.qpDownloadUrl, "${d.assignmentTitle}_question") }) { Text("Question Paper") }
            if (d.canDaDownload && d.daDownloadUrl.isNotEmpty()) TextButton(enabled = !busy, onClick = { onDownload(d.daDownloadUrl, "${d.assignmentTitle}_submission") }) { Text("My Submission") }
        }
    }
}
