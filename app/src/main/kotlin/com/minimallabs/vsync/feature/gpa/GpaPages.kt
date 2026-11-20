package com.minimallabs.vsync.feature.gpa

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.minimallabs.vsync.core.App
import com.minimallabs.vsync.feature.academics.trimNumber
import com.minimallabs.vsync.ui.AppCard
import com.minimallabs.vsync.ui.EmptyState
import com.minimallabs.vsync.ui.KeyValue
import com.minimallabs.vsync.ui.LocalNav
import com.minimallabs.vsync.ui.LocalStatus
import com.minimallabs.vsync.ui.Muted
import com.minimallabs.vsync.ui.Page
import com.minimallabs.vsync.ui.SectionTitle

private fun f2(v: Double) = "%.2f".format(v)

/** One even row of grade boxes (never a wrapping row that strands "F"). */
@Composable
fun GradeStrip(selected: PlannerGrade?, onPick: (PlannerGrade) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (g in PlannerGrade.entries) {
            val on = g == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onPick(g) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text(g.letter, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge) }
        }
    }
}

@Composable
private fun Stepper(value: Double?, onChange: (Double) -> Unit) {
    // Unknown credits read "? cr" and the first tap sets 3.
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { if (value == null) onChange(3.0) else if (value > 1) onChange(value - 1) }) { Icon(Icons.Filled.Remove, "Fewer credits") }
        Text(if (value == null) "? cr" else "${trimNumber(value)} cr", style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = { if (value == null) onChange(3.0) else if (value < 12) onChange(value + 1) }) { Icon(Icons.Filled.Add, "More credits") }
    }
}

private class Added(val id: Int, name: String, credits: Double) {
    var name by mutableStateOf(name)
    var credits by mutableDoubleStateOf(credits)
}

@Composable
fun GpaPlannerPage() {
    val nav = LocalNav.current
    val gh = App.user?.profile?.gradeHistory
    val cgpa = gh?.cgpa?.toDoubleOrNull() ?: 0.0
    val done = SemesterCourses.creditsOf(gh?.creditsEarned) ?: 0.0
    val timetable = App.user?.timetable
    val fromTable = remember(timetable) { SemesterCourses.from(timetable) }
    val complete = SemesterCourses.isComplete(timetable)

    var target by remember { mutableDoubleStateOf(((cgpa + 0.2).coerceAtMost(10.0) * 10).toInt() / 10.0) }
    val credits = remember { mutableStateMapOf<String, Double>() }      // the student's value wins
    val grades = remember { mutableStateMapOf<String, PlannerGrade>() }
    val removed = remember { mutableStateListOf<String>() }
    val added = remember { mutableStateListOf<Added>() }
    var nextId by remember { mutableStateOf(1) }

    val active = fromTable.filter { it.code !in removed }
    val creditOf = { c: SemesterCourse -> credits[c.code] ?: c.credits }
    val semCredits = active.sumOf { creditOf(it) ?: 0.0 } + added.sumOf { it.credits }
    val goal = GpaPlanner.goal(cgpa, done, semCredits, target)
    val suggested = goal?.suggested ?: PlannerGrade.A
    val planned = active.mapNotNull { c -> creditOf(c)?.let { it to (grades[c.code] ?: suggested) } } +
        added.map { it.credits to (grades["+${it.id}"] ?: suggested) }
    val projection = GpaPlanner.project(cgpa, done, planned)

    Page("GPA Planner") {
        AppCard(onClick = { nav.push { ImproveCgpaPage() } }) {
            Text("Improve CGPA", style = MaterialTheme.typography.titleSmall)
            Muted("Try different grades for past courses and see your CGPA change")
        }
        AppCard {
            KeyValue("Current CGPA (VTOP)", gh?.cgpa ?: "-")
            KeyValue("Credits earned", trimNumber(done))
            Spacer(Modifier.height(8.dp))
            Text("Target CGPA: ${"%.1f".format(target)}", style = MaterialTheme.typography.titleMedium)
            Slider(value = target.toFloat(), onValueChange = { target = Math.round(it * 10) / 10.0 }, valueRange = 5f..10f)
            if (goal != null) {
                val s = LocalStatus.current
                Text(
                    when {
                        goal.alreadyThere -> "You are already above this target."
                        !goal.reachable -> "Not reachable this semester (best possible ${f2(goal.best)})."
                        else -> "You need a ${f2(goal.required)} GPA this semester."
                    },
                    color = if (!goal.reachable) s.danger else s.success, style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (!complete && fromTable.isNotEmpty()) Muted("This timetable was saved before course credits were kept. Refresh the timetable for exact credits.")
        if (removed.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
            Muted("${removed.size} course${if (removed.size == 1) "" else "s"} removed", Modifier.weight(1f))
            TextButton(onClick = { removed.clear() }) { Text("Restore") }
        }
        if (active.isEmpty() && added.isEmpty()) EmptyState("Nothing to plan", "Add a course, or refresh your timetable.")
        if (planned.isNotEmpty()) AppCard {
            KeyValue("Semester credits", trimNumber(projection.semesterCredits))
            KeyValue("Semester GPA", f2(projection.semesterGpa))
            KeyValue("New CGPA", f2(projection.newCgpa))
            KeyValue("Change", (if (projection.change >= 0) "+" else "") + f2(projection.change))
        }
        for (c in active) AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                    Muted(c.code)
                }
                IconButton(onClick = { removed.add(c.code) }) { Icon(Icons.Filled.Close, "Remove") }
            }
            val cr = creditOf(c)
            if (cr == null) Muted("Credits unknown: set them to include this course")
            Stepper(cr) { credits[c.code] = it }
            GradeStrip(grades[c.code] ?: suggested) { grades[c.code] = it }
        }
        for (a in added) AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(a.name, { a.name = it }, label = { Text("Course name") }, singleLine = true, modifier = Modifier.weight(1f))
                IconButton(onClick = { added.remove(a) }) { Icon(Icons.Filled.Close, "Remove") }
            }
            Stepper(a.credits) { a.credits = it }
            GradeStrip(grades["+${a.id}"] ?: suggested) { grades["+${a.id}"] = it }
        }
        TextButton(onClick = { added.add(Added(nextId++, "", 3.0)) }) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add a course") }
    }
}

@Composable
fun ImproveCgpaPage() {
    val gh = App.user?.profile?.gradeHistory
    val sessions = remember(gh) { gh?.let { CgpaImprover.sessions(it) } ?: emptyList() }
    val picks = remember { mutableStateMapOf<String, PlannerGrade>() }
    val vtop = gh?.cgpa?.toDoubleOrNull() ?: 0.0
    val outcome = CgpaImprover.cgpa(vtop, sessions, picks)
    Page("Improve CGPA") {
        if (sessions.isEmpty()) { EmptyState("No graded courses yet"); return@Page }
        AppCard {
            KeyValue("CGPA now (VTOP)", f2(outcome.now))
            KeyValue("CGPA after", f2(outcome.after))
            KeyValue("Change", (if (outcome.change >= 0) "+" else "") + f2(outcome.change))
            if (!outcome.matchesVtop) Muted("Worked out from your grade list; VTOP's own rules may differ slightly.")
            if (picks.isNotEmpty()) TextButton(onClick = { picks.clear() }) { Text("Reset") }
        }
        for (s in sessions) {
            val so = CgpaImprover.session(s, picks)
            SectionTitle(s.label)
            if (so.now != null) Muted("SGPA ${f2(so.now)}" + if (so.after != null && Math.abs(so.after - so.now) > 0.0001) " → ${f2(so.after)}" else "")
            for (c in s.courses) AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.title, style = MaterialTheme.typography.bodyMedium)
                        Muted("${c.code} · ${trimNumber(c.credits)} credits" + if (c.superseded) " · re-taken later" else "")
                    }
                    Text(c.grade, style = MaterialTheme.typography.titleMedium, color = if (c.superseded) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified)
                }
                if (c.canChange) GradeStrip(picks[c.key] ?: c.gradeValue) { g -> if (g == c.gradeValue) picks.remove(c.key) else picks[c.key] = g }
            }
        }
    }
}
