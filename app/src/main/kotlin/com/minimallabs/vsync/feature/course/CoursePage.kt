package com.minimallabs.vsync.feature.course

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.minimallabs.vsync.core.App
import com.minimallabs.vsync.core.DemoMode
import com.minimallabs.vsync.core.Files
import com.minimallabs.vsync.data.AppJson
import com.minimallabs.vsync.data.CourseClassEntry
import com.minimallabs.vsync.data.CourseOption
import com.minimallabs.vsync.data.CoursePageDetail
import com.minimallabs.vsync.data.CoursesResponse
import com.minimallabs.vsync.data.SlotsResponse
import com.minimallabs.vsync.ui.AppCard
import com.minimallabs.vsync.ui.CenterLoader
import com.minimallabs.vsync.ui.EmptyState
import com.minimallabs.vsync.ui.ErrorLine
import com.minimallabs.vsync.ui.KeyValue
import com.minimallabs.vsync.ui.LocalNav
import com.minimallabs.vsync.ui.Muted
import com.minimallabs.vsync.ui.Page
import com.minimallabs.vsync.ui.SectionTitle
import com.minimallabs.vsync.ui.rememberLoader
import kotlinx.coroutines.launch

private fun typeLabel(type: String) = when (type.uppercase()) {
    "ETH" -> "Embedded Theory"
    "ELA" -> "Embedded Lab"
    "TH" -> "Theory"
    "LO" -> "Lab Only"
    "PJT" -> "Project"
    "SS" -> "Soft Skill"
    else -> type
}

/** Course picker -> class entries (slots) -> lectures and material. All live from VTOP. */
@Composable
fun CoursePage() {
    val nav = LocalNav.current
    val loader = rememberLoader<CoursesResponse>()
    fun load() = loader.run {
        if (DemoMode.isOn) DemoMode.section("courses")
        else { val sem = App.semesterId; AppJson.decodeFromString(App.vtop { it.text("courses_for_course_page", sem) }) }
    }
    LaunchedEffect(Unit) { load() }
    Page("Course Page") {
        val courses = loader.value?.courses
        when {
            courses == null && loader.error != null -> EmptyState("Couldn't load courses", loader.error, "Retry") { load() }
            courses == null -> CenterLoader()
            courses.isEmpty() -> EmptyState("No courses found", "VTOP lists no courses for this semester.")
            else -> for (c in courses) AppCard(onClick = { nav.push { SlotsPage(c) } }) {
                Text("${c.courseTitle} - ${typeLabel(c.courseType)}", style = MaterialTheme.typography.titleSmall)
                Muted(c.courseCode)
            }
        }
    }
}

@Composable
private fun SlotsPage(course: CourseOption) {
    val nav = LocalNav.current
    val loader = rememberLoader<SlotsResponse>()
    fun load() = loader.run {
        if (DemoMode.isOn) DemoMode.section("slots")
        else { val sem = App.semesterId; AppJson.decodeFromString(App.vtop { it.text("slots_for_course_page", sem, course.value) }) }
    }
    LaunchedEffect(course.value) { load() }
    Page(course.courseCode.ifBlank { "Classes" }) {
        Text(course.courseTitle, style = MaterialTheme.typography.titleMedium)
        val entries = loader.value?.classEntries
        when {
            entries == null && loader.error != null -> EmptyState("Couldn't load classes", loader.error, "Retry") { load() }
            entries == null -> CenterLoader()
            entries.isEmpty() -> EmptyState("No classes found")
            else -> for (e in entries) AppCard(onClick = { nav.push { LecturesPage(e) } }) {
                Text(e.faculty.ifBlank { "Faculty" }, style = MaterialTheme.typography.titleSmall)
                Muted("${typeLabel(e.courseType)} · ${e.slot}")
                Muted(e.classGroup)
            }
        }
    }
}

@Composable
private fun LecturesPage(entry: CourseClassEntry) {
    val scope = rememberCoroutineScope()
    val loader = rememberLoader<CoursePageDetail>()
    var busy by remember { mutableStateOf<String?>(null) }
    fun load() = loader.run {
        if (DemoMode.isOn) DemoMode.section("course_detail")
        else { val sem = App.semesterId; AppJson.decodeFromString(App.vtop { it.text("course_detail", sem, entry.erpId, entry.classId) }) }
    }
    LaunchedEffect(entry.classId) { load() }

    fun download(key: String, name: String, label: String, extension: String? = null, fetch: suspend () -> ByteArray) {
        if (busy != null) return
        busy = key
        scope.launch {
            Files.fetchSaveOpen(name, label, extension, fetch)
            busy = null
        }
    }

    Page(entry.courseCode.ifBlank { "Lectures" }) {
        val d = loader.value
        when {
            d == null && loader.error != null -> { EmptyState("Couldn't load the course page", loader.error, "Retry") { load() }; return@Page }
            d == null -> { CenterLoader(); return@Page }
        }
        val info = d!!.courseInfo
        val code = info.courseCode.ifBlank { entry.courseCode }
        AppCard {
            Text(info.courseTitle.ifBlank { entry.courseTitle }, style = MaterialTheme.typography.titleMedium)
            KeyValue("Faculty", info.faculty.ifBlank { entry.faculty })
            KeyValue("Slot", info.slot.ifBlank { entry.slot })
            KeyValue("Type", typeLabel(info.courseType))
            Row {
                d.downloadAllPath?.takeIf { it.isNotBlank() }?.let { path ->
                    TextButton(enabled = busy == null, onClick = {
                        download("all", "${code}_all_materials", "Course Materials (ZIP)", "zip") { App.vtop { it.bytes("download_all_course_materials", path) } }
                    }) { Text(if (busy == "all") "Downloading..." else "All materials") }
                }
                // The parsed syllabus path is used directly: rebuilding it from the
                // course type fails when VTOP gives the long name.
                d.syllabusDownloadPath?.takeIf { it.isNotBlank() }?.let { path ->
                    TextButton(enabled = busy == null, onClick = {
                        download("syllabus", "${code}_syllabus", "Course Syllabus") { App.vtop { it.bytes("download_course_material", path) } }
                    }) { Text(if (busy == "syllabus") "Downloading..." else "Syllabus") }
                }
            }
        }
        SectionTitle("Lectures")
        if (d.lectures.isEmpty()) Muted("No lectures posted yet.")
        for (l in d.lectures) AppCard {
            Muted("${l.slNo}. ${l.formattedDate.ifBlank { l.date }} · ${l.day}")
            Text(l.topic.ifBlank { "No topic" }, style = MaterialTheme.typography.bodyLarge)
            if (l.referenceMaterials.isNotEmpty()) Spacer(Modifier.height(4.dp))
            for (m in l.referenceMaterials) {
                val key = m.downloadPath
                TextButton(enabled = busy == null, onClick = {
                    download(key, "${code}_${m.label.ifBlank { "material" }}", "Course Material") { App.vtop { it.bytes("download_course_material", m.downloadPath) } }
                }) { Text(if (busy == key) "Downloading..." else m.label.ifBlank { "Material" }) }
            }
        }
    }
}
