package com.parentops.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val DF = DateTimeFormatter.ofPattern("EEE dd MMM")
private val DDMON = DateTimeFormatter.ofPattern("dd-MMM-yyyy")
private val FULLDAY = DateTimeFormatter.ofPattern("EEEE, dd-MMM-yyyy")
private fun nice(iso: String?): String =
    try { LocalDate.parse(iso).format(DF) } catch (e: Exception) { "No date" }
private fun dow(iso: String?): String =
    try { LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("EEE")) } catch (e: Exception) { "—" }

@Composable
fun App(
    data: AppData,
    syncing: Boolean,
    pendingShare: String?,
    onMutate: ((AppData) -> Unit) -> Unit,
    onAddChild: () -> Unit,
    onSync: () -> Unit,
    onIngestShare: (String?, Boolean) -> Unit,
    onDismissShare: () -> Unit,
    onOpenLink: (String?) -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    var selChild by remember { mutableStateOf<String?>(null) }

    if (pendingShare != null) {
        var asTimetable by remember(pendingShare) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = onDismissShare,
            title = { Text("Add to ParentOps") },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { asTimetable = !asTimetable }) {
                        Checkbox(checked = asTimetable,
                            onCheckedChange = { asTimetable = it })
                        Text("📅 This is a timetable", fontSize = 14.sp)
                    }
                    if (data.children.size > 1) {
                        Text(if (asTimetable) "Whose timetable?" else "Whose is this?",
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 8.dp))
                        data.children.forEach { ch ->
                            OutlinedButton(onClick = { onIngestShare(ch.email, asTimetable) },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Text(ch.name)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(pendingShare.take(400), fontSize = 12.sp,
                        color = Color(0xFF6B7280))
                }
            },
            confirmButton = {
                if (data.children.size <= 1) {
                    TextButton(onClick = { onIngestShare(null, asTimetable) }) { Text("Add") }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissShare) { Text("Cancel") }
            })
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                listOf("🏠 Today", "☀️ Digest", "✅ Done", "📚 Library", "⚙️ Settings")
                    .forEachIndexed { i, label ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Text(label.substringBefore(" "), fontSize = 18.sp) },
                            label = { Text(label.substringAfter(" "), fontSize = 11.sp) })
                    }
            }
        }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Header(syncing, onSync)
            when (tab) {
                0 -> TodayScreen(data, selChild, { selChild = it }, onMutate, onOpenLink, onAddChild)
                1 -> DigestScreen(data, onMutate, onOpenLink)
                2 -> DoneScreen(data, onMutate)
                3 -> LibraryScreen(data, onOpenLink)
                4 -> SettingsScreen(data, onMutate, onAddChild, onSync)
            }
        }
    }
}

@Composable
private fun Header(syncing: Boolean, onSync: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Row {
            Text("Parent", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Text("Ops", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary)
        }
        if (syncing) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        } else {
            TextButton(onClick = onSync) { Text("⟳ Sync") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KidChips(data: AppData, selChild: String?, onSelect: (String?) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        FilterChip(selected = selChild == null, onClick = { onSelect(null) },
            label = { Text("All kids") }, modifier = Modifier.padding(4.dp))
        data.children.forEach { ch ->
            FilterChip(selected = selChild == ch.email, onClick = { onSelect(ch.email) },
                label = { Text(ch.name.substringBefore(" ")) },
                modifier = Modifier.padding(4.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String, color: Color = Color(0xFF6B7280)) {
    Text(text.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold,
        color = color, letterSpacing = 1.sp,
        modifier = Modifier.padding(16.dp, 14.dp, 16.dp, 6.dp))
}

@Composable
private fun ItemCard(
    it: ActionItem, child: Child?, today: String,
    onMutate: ((AppData) -> Unit) -> Unit, onOpenLink: (String?) -> Unit,
    postLink: String?,
) {
    Card(
        Modifier.fillMaxWidth().padding(12.dp, 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Row(Modifier.padding(12.dp)) {
            val overdue = it.dueDate != null && it.dueDate!! < today
            Column(
                Modifier.width(52.dp)
                    .background(
                        if (overdue) Color(0xFFFEF1F0) else Color(0xFFEEF0FE),
                        RoundedCornerShape(10.dp))
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(dow(it.dueDate), fontWeight = FontWeight.ExtraBold, fontSize = 13.sp,
                    color = if (overdue) Color(0xFFD92D20) else Color(0xFF4653E8))
                Text(it.dueDate?.takeLast(5) ?: "—", fontSize = 10.sp,
                    color = if (overdue) Color(0xFFD92D20) else Color(0xFF4653E8))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(it.title, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        modifier = Modifier.weight(1f))
                    // Dismiss lives quietly in the corner, not in the button row
                    Text("✕", fontSize = 14.sp, color = Color(0xFF9CA3AF),
                        modifier = Modifier
                            .clickable { onMutate { _ -> it.status = "dismissed" } }
                            .padding(start = 8.dp, top = 2.dp))
                }
                it.detail?.let { d ->
                    Text(d, fontSize = 12.5.sp, color = Color(0xFF4B5563),
                        lineHeight = 17.sp, maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp))
                }
                it.checklist.forEach { entry ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = entry.done, onCheckedChange = { v ->
                            onMutate { entry.done = v }
                        }, modifier = Modifier.size(32.dp))
                        Text(entry.text, fontSize = 13.sp,
                            textDecoration = if (entry.done) TextDecoration.LineThrough else null,
                            color = if (entry.done) Color(0xFF6B7280) else Color.Unspecified)
                    }
                }
                Row(Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    child?.let { c ->
                        Tag(c.name.substringBefore(" "), Color(c.color))
                        Spacer(Modifier.width(6.dp))
                    }
                    it.amount?.let { a -> Tag(a, Color(0xFF067647)); Spacer(Modifier.width(6.dp)) }
                    it.category?.takeIf { c -> c != "task" }?.let { c -> Tag(c, Color(0xFF6B7280)) }
                    Spacer(Modifier.weight(1f))
                    if (postLink != null) {
                        Text("Original ↗", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            color = Color(0xFF4653E8),
                            modifier = Modifier
                                .clickable { onOpenLink(postLink) }
                                .padding(end = 10.dp))
                    }
                    Text("Done ✓", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier
                            .background(Color(0xFF4653E8), RoundedCornerShape(8.dp))
                            .clickable {
                                onMutate { _ ->
                                    it.status = "done"; it.doneAt = LocalDate.now().toString()
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(99.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodayScreen(
    data: AppData, selChild: String?, onSelect: (String?) -> Unit,
    onMutate: ((AppData) -> Unit) -> Unit, onOpenLink: (String?) -> Unit,
    onAddChild: () -> Unit,
) {
    val todayDate = LocalDate.now()
    val today = todayDate.toString()
    var selDate by remember { mutableStateOf(todayDate) }
    var showPicker by remember { mutableStateOf(false) }

    val open = data.items.filter {
        it.status == "open" && (selChild == null || it.childEmail == selChild)
    }
    val selIso = selDate.toString()
    val onDay = open.filter { it.dueDate == selIso && it.category != "holiday" }
    val holidays = open.filter { it.category == "holiday" && it.dueDate == selIso }
    val holidayKids = holidays.map { it.childEmail }.toSet()
    // Everything actionable that isn't already shown for the selected day:
    // overdue first, then upcoming submissions/tasks, undated last.
    val todos = open.filter { it.category != "holiday" && it.dueDate != selIso }
        .sortedWith(compareBy({ it.dueDate == null }, { it.dueDate ?: "9999-99-99" }))
    val notices = data.posts.filter {
        (selChild == null || it.childEmail == selChild) &&
        it.postedAt.take(10) == selIso
    }
    val childOf = data.children.associateBy { it.email }
    val postOf = data.posts.associateBy { it.key }
    val dayKey = Extract.WEEKDAY_KEYS[selDate.dayOfWeek.value - 1]

    if (showPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = selDate.atStartOfDay(ZoneOffset.UTC)
                .toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                // Past notices are browsable; future dates are disabled.
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val d = Instant.ofEpochMilli(utcTimeMillis)
                        .atZone(ZoneOffset.UTC).toLocalDate()
                    return !d.isAfter(LocalDate.now())
                }
            })
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        selDate = Instant.ofEpochMilli(it)
                            .atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            }) {
            DatePicker(state = pickerState)
        }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item { KidChips(data, selChild, onSelect) }

        if (data.children.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Welcome to ParentOps 👋", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text("Sign in with your child's school Google account to pull their Classroom.",
                            fontSize = 13.sp, color = Color(0xFF6B7280),
                            modifier = Modifier.padding(vertical = 8.dp))
                        Button(onClick = onAddChild) { Text("Sign in with Google") }
                        Text("School blocks sign-in? No problem — share any screenshot " +
                             "or WhatsApp message to ParentOps and it becomes an action " +
                             "item. Add children in Settings.",
                            fontSize = 12.sp, color = Color(0xFF6B7280),
                            modifier = Modifier.padding(top = 10.dp))
                    }
                }
            }
        }

        // Date header: DD-Mon-YYYY, tap to open the calendar picker.
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp, 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.clickable { showPicker = true }) {
                    Text(selDate.format(DDMON), fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold)
                    Text(selDate.format(DateTimeFormatter.ofPattern("EEEE")) +
                         if (selDate == todayDate) " · Today" else "",
                        fontSize = 12.sp, color = Color(0xFF6B7280))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selDate != todayDate) {
                        Text("Today", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            color = Color(0xFF4653E8),
                            modifier = Modifier
                                .clickable { selDate = todayDate }
                                .padding(end = 12.dp))
                    }
                    Text("📅", fontSize = 22.sp,
                        modifier = Modifier.clickable { showPicker = true })
                }
            }
        }

        if (holidays.isNotEmpty()) {
            item {
                Card(Modifier.fillMaxWidth().padding(12.dp, 4.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF59E0B))) {
                    Column(Modifier.padding(14.dp)) {
                        Text("🎉 Holiday — no school", color = Color.White,
                            fontWeight = FontWeight.ExtraBold)
                        holidays.map { it.title }.distinct().forEach {
                            Text(it, color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // Timetable for the selected day's weekday.
        val tt = data.children.filter {
            (selChild == null || it.email == selChild) &&
            it.email !in holidayKids && it.timetable.containsKey(dayKey)
        }
        if (tt.isNotEmpty()) {
            item { SectionTitle("Timetable") }
            tt.forEach { ch ->
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Tag(ch.name.substringBefore(" "), Color(ch.color))
                        ch.timetable[dayKey]?.forEach { p ->
                            Spacer(Modifier.width(6.dp))
                            Text(p, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .background(Color.White, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                    }
                }
            }
        }

        // 1. Events of the selected day.
        item { SectionTitle("Events — ${selDate.format(DDMON)}") }
        if (onDay.isEmpty() && holidays.isEmpty()) {
            item {
                Text("No events this day ✨", fontSize = 13.sp, color = Color(0xFF6B7280),
                    modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
        onDay.forEach { ai ->
            item {
                ItemCard(ai, childOf[ai.childEmail], today, onMutate, onOpenLink,
                    ai.postKey?.let { postOf[it]?.link })
            }
        }

        // 2. Notices/messages posted on the selected day (browse past days).
        if (notices.isNotEmpty()) {
            item { SectionTitle("Notices posted this day") }
            notices.forEach { p ->
                item {
                    Card(Modifier.fillMaxWidth().padding(12.dp, 4.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier
                            .clickable { onOpenLink(p.link) }
                            .padding(12.dp)) {
                            Text(
                                (when (p.kind) {
                                    "announcement" -> "📣 "
                                    "courseWork" -> "📝 "
                                    "material" -> "📘 "
                                    else -> "📎 "
                                }) + p.title,
                                fontWeight = FontWeight.Bold, fontSize = 13.5.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Row(Modifier.padding(top = 4.dp)) {
                                childOf[p.childEmail]?.let { c ->
                                    Tag(c.name.substringBefore(" "), Color(c.color))
                                    Spacer(Modifier.width(6.dp))
                                }
                                Tag(p.courseName, Color(0xFF6B7280))
                            }
                        }
                    }
                }
            }
        }

        // 3. To-Do: everything open — submissions, messages, announcements.
        item { SectionTitle("To-Do", Color(0xFF4653E8)) }
        if (todos.isEmpty()) {
            item {
                Text("All clear — nothing pending 🎉", fontSize = 13.sp,
                    color = Color(0xFF6B7280),
                    modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
        todos.forEach { ai ->
            item {
                ItemCard(ai, childOf[ai.childEmail], today, onMutate, onOpenLink,
                    ai.postKey?.let { postOf[it]?.link })
            }
        }

        val doneToday = data.items.count { it.status == "done" && it.doneAt == today }
        if (doneToday > 0) {
            item {
                Text("✅ $doneToday finished today",
                    color = Color(0xFF067647), fontWeight = FontWeight.Bold,
                    fontSize = 13.sp, modifier = Modifier.padding(16.dp))
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun DigestScreen(
    data: AppData,
    onMutate: ((AppData) -> Unit) -> Unit, onOpenLink: (String?) -> Unit,
) {
    var evening by remember { mutableStateOf(false) }
    val today = LocalDate.now().toString()
    val tomorrow = LocalDate.now().plusDays(1).toString()
    val open = data.items.filter { it.status == "open" }
    val childOf = data.children.associateBy { it.email }
    val postOf = data.posts.associateBy { it.key }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(12.dp)) {
                OutlinedButton(onClick = { evening = false },
                    enabled = evening) { Text("☀️ Morning") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { evening = true },
                    enabled = !evening) { Text("🌙 Evening") }
            }
        }
        item {
            Text(LocalDate.now().format(FULLDAY),
                fontSize = 15.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(16.dp, 0.dp, 16.dp, 4.dp))
        }
        fun block(title: String, list: List<ActionItem>, color: Color = Color(0xFF6B7280)) {
            if (list.isEmpty()) return
            item { SectionTitle(title, color) }
            list.forEach { ai ->
                item {
                    ItemCard(ai, childOf[ai.childEmail], today, onMutate, onOpenLink,
                        ai.postKey?.let { postOf[it]?.link })
                }
            }
        }
        if (!evening) {
            block("Events today", open.filter { it.dueDate == today })
            block("Carried over", open.filter { it.dueDate != null && it.dueDate!! < today },
                Color(0xFFD92D20))
        } else {
            val doneToday = data.items.filter { it.status == "done" && it.doneAt == today }
            item { SectionTitle("Done today — ${doneToday.size}", Color(0xFF067647)) }
            doneToday.forEach { ai ->
                item {
                    Text("✓ ${ai.title}", color = Color(0xFF067647),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }
            block("Still pending", open.filter { it.dueDate != null && it.dueDate!! <= today },
                Color(0xFFD92D20))
            block("Tomorrow — prepare tonight", open.filter { it.dueDate == tomorrow })
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun DoneScreen(data: AppData, onMutate: ((AppData) -> Unit) -> Unit) {
    val done = data.items.filter { it.status == "done" }
        .sortedByDescending { it.doneAt ?: "" }
    val childOf = data.children.associateBy { it.email }
    LazyColumn(Modifier.fillMaxSize()) {
        if (done.isEmpty()) {
            item {
                Text("No finished tasks yet — items you mark Done ✓ land here.",
                    color = Color(0xFF6B7280), fontSize = 13.sp,
                    modifier = Modifier.padding(20.dp))
            }
        }
        done.groupBy { it.doneAt ?: "unknown" }.forEach { (day, group) ->
            item {
                SectionTitle(
                    if (day == LocalDate.now().toString()) "Today" else nice(day),
                    Color(0xFF067647))
            }
            group.forEach { ai ->
                item {
                    Card(Modifier.fillMaxWidth().padding(12.dp, 4.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Row(Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("✓ ${ai.title}", fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp, color = Color(0xFF067647))
                                Row(Modifier.padding(top = 4.dp)) {
                                    childOf[ai.childEmail]?.let { c ->
                                        Tag(c.name.substringBefore(" "), Color(c.color))
                                    }
                                    ai.dueDate?.let {
                                        Spacer(Modifier.width(6.dp))
                                        Tag("was due ${nice(it)}", Color(0xFF6B7280))
                                    }
                                }
                            }
                            TextButton(onClick = {
                                onMutate { _ -> ai.status = "open"; ai.doneAt = null }
                            }) { Text("Undo") }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun LibraryScreen(data: AppData, onOpenLink: (String?) -> Unit) {
    var query by remember { mutableStateOf("") }
    val childOf = data.children.associateBy { it.email }
    val shown = data.posts.filter {
        query.isBlank() ||
        it.title.contains(query, true) || it.body.contains(query, true) ||
        it.courseName.contains(query, true)
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text("Search circulars, homework, syllabus…") },
                modifier = Modifier.fillMaxWidth().padding(12.dp))
        }
        if (query.isBlank()) {
            item { SectionTitle("Subjects") }
            data.posts.groupBy { it.childEmail to it.courseName }.forEach { (k, posts) ->
                item {
                    Row(Modifier.fillMaxWidth()
                        .clickable { query = k.second }
                        .padding(16.dp, 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        childOf[k.first]?.let { c ->
                            Tag(c.name.substringBefore(" "), Color(c.color))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(k.second, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                            modifier = Modifier.weight(1f))
                        Text("${posts.size}", color = Color(0xFF6B7280), fontSize = 12.sp)
                    }
                }
            }
        }
        item { SectionTitle(if (query.isBlank()) "Recent posts" else "Results") }
        shown.take(100).forEach { p ->
            item {
                Card(Modifier.fillMaxWidth().padding(12.dp, 4.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(12.dp)) {
                        Row {
                            childOf[p.childEmail]?.let { c ->
                                Tag(c.name.substringBefore(" "), Color(c.color))
                                Spacer(Modifier.width(6.dp))
                            }
                            Tag(p.courseName, Color(0xFF6B7280))
                            Spacer(Modifier.width(6.dp))
                            Tag(p.kind, Color(0xFF6B7280))
                        }
                        Text(p.title, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                            modifier = Modifier.padding(top = 6.dp))
                        if (p.body.isNotBlank() && p.body != p.title) {
                            Text(p.body.take(300), fontSize = 12.sp,
                                color = Color(0xFF6B7280),
                                modifier = Modifier.padding(top = 4.dp))
                        }
                        p.attachments.forEach { att ->
                            TextButton(onClick = { onOpenLink(att.link) }) {
                                Text("📎 ${att.title.take(40)}", fontSize = 12.sp)
                            }
                        }
                        if (p.link != null) {
                            TextButton(onClick = { onOpenLink(p.link) }) {
                                Text("Open in Classroom ↗", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SettingsScreen(
    data: AppData, onMutate: ((AppData) -> Unit) -> Unit,
    onAddChild: () -> Unit, onSync: () -> Unit,
) {
    var editChild by remember { mutableStateOf<Child?>(null) }
    var addManual by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionTitle("Children") }
        data.children.forEach { ch ->
            item {
                Card(Modifier.fillMaxWidth().padding(12.dp, 4.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(ch.name, fontWeight = FontWeight.Bold)
                            Text(ch.email, fontSize = 12.sp, color = Color(0xFF6B7280))
                        }
                        TextButton(onClick = { editChild = ch }) { Text("Timetable ✏️") }
                        TextButton(onClick = {
                            onMutate { d ->
                                d.children.remove(ch)
                                d.posts.removeAll { it.childEmail == ch.email }
                                d.items.removeAll { it.childEmail == ch.email }
                            }
                        }) { Text("Remove", color = Color(0xFFD92D20)) }
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(16.dp, 8.dp)) {
                Button(onClick = onAddChild) {
                    Text("+ Sign in with Google (add a child)")
                }
                OutlinedButton(onClick = { addManual = true },
                    modifier = Modifier.padding(top = 4.dp)) {
                    Text("+ Add a child without Google")
                }
                Text("No Google needed: share screenshots or WhatsApp texts to " +
                     "ParentOps from any app and they become action items here.",
                    fontSize = 11.sp, color = Color(0xFF6B7280),
                    modifier = Modifier.padding(top = 6.dp))
            }
        }
        item { SectionTitle("Sync") }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("Auto-sync runs every ~6 hours in the background.", fontSize = 13.sp)
                Text("Last sync: ${data.lastSync ?: "never"}", fontSize = 13.sp,
                    color = Color(0xFF6B7280))
                data.lastSyncError?.let {
                    Text("⚠ $it", fontSize = 12.sp, color = Color(0xFFB54708))
                }
                OutlinedButton(onClick = onSync, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Sync now")
                }
            }
        }
        item { SectionTitle("About") }
        item {
            Text("All data stays on this phone. Google access is read-only and " +
                 "revocable at myaccount.google.com/permissions.",
                fontSize = 12.sp, color = Color(0xFF6B7280),
                modifier = Modifier.padding(16.dp, 4.dp))
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (addManual) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addManual = false },
            title = { Text("Add a child (no Google account)") },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it },
                        label = { Text("Child's name") })
                    Text("You'll feed their items by sharing screenshots or texts " +
                         "to ParentOps — no sign-in involved.",
                        fontSize = 11.sp, color = Color(0xFF6B7280),
                        modifier = Modifier.padding(top = 6.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        onMutate { d ->
                            d.children.add(Child(
                                email = "manual-" + name.trim().lowercase()
                                    .replace(Regex("[^a-z0-9]+"), "-") +
                                    "-" + System.currentTimeMillis() % 10000,
                                name = name.trim(),
                                color = CHILD_COLORS[d.children.size % CHILD_COLORS.size]))
                        }
                    }
                    addManual = false
                }) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { addManual = false }) { Text("Cancel") }
            })
    }

    editChild?.let { ch ->
        var name by remember(ch) { mutableStateOf(ch.name) }
        var tt by remember(ch) {
            mutableStateOf(ch.timetable.entries.joinToString("\n") { (k, v) ->
                "$k: ${v.joinToString(", ")}"
            })
        }
        AlertDialog(
            onDismissRequest = { editChild = null },
            title = { Text("Edit ${ch.name}") },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it },
                        label = { Text("Name") })
                    OutlinedTextField(value = tt, onValueChange = { tt = it },
                        label = { Text("Timetable (mon: Math, PT, …)") },
                        minLines = 5, modifier = Modifier.padding(top = 8.dp))
                    Text("One line per day (mon–sat). Saturday line = Saturday school " +
                         "(affects how \"tomorrow\" on a Friday is read).",
                        fontSize = 11.sp, color = Color(0xFF6B7280))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onMutate { _ ->
                        ch.name = name.ifBlank { ch.name }
                        ch.timetable = tt.lines().mapNotNull { line ->
                            val parts = line.split(":", limit = 2)
                            if (parts.size == 2 && parts[0].trim().lowercase() in Extract.WEEKDAY_KEYS) {
                                parts[0].trim().lowercase() to
                                    parts[1].split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            } else null
                        }.toMap()
                    }
                    editChild = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editChild = null }) { Text("Cancel") }
            })
    }
}
