package com.parentops.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * On-device extraction of action items from Classroom posts — a Kotlin port
 * of the server heuristic: explicit dates ("03.07.2026", "20-Jul-2026"),
 * "tomorrow" resolved to the next school day (Friday -> Monday unless the
 * child's timetable has Saturday classes), amounts, bullets, categories.
 */
object Extract {

    private val ACTION = Regex(
        "\\b(submit|bring|send|pay|due|last date|deadline|test|exam|fill|wear|" +
        "attend|meeting|holiday|fee|fees|remind|prepare|complete|carry)\\b",
        RegexOption.IGNORE_CASE)
    private val DATE_NUM = Regex("\\b(\\d{1,2})[./-](\\d{1,2})[./-](\\d{2,4})\\b")
    private val DATE_WORD = Regex("\\b(\\d{1,2})[ \\-]([A-Za-z]{3,9})[ \\-,]+(\\d{4})\\b")
    private val HOLIDAY = Regex("\\bholiday|school (will remain |remains )?closed\\b", RegexOption.IGNORE_CASE)
    private val AMOUNT = Regex("(?:₹|rs\\.?|inr)\\s?([\\d,]+)", RegexOption.IGNORE_CASE)
    private val BULLET = Regex("^\\s*(?:[•\\-*]|\\d+[.)])\\s+(.{3,120})$", RegexOption.MULTILINE)
    private val TOMORROW = Regex("\\btomorrow\\b", RegexOption.IGNORE_CASE)

    private val MONTHS = (1..12).associateBy {
        java.time.Month.of(it).getDisplayName(TextStyle.SHORT, Locale.ENGLISH).lowercase()
    }

    val WEEKDAY_KEYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
    private val DEFAULT_SCHOOL_DAYS = listOf("mon", "tue", "wed", "thu", "fri")
    private val GREETING = Regex(
        "^(dear|respected|hello|hi|greetings)\\b|^(regards|thanks|thank you)\\b",
        RegexOption.IGNORE_CASE)

    /** First meaningful line — skips "Dear Parents," style greetings. */
    fun headline(text: String): String {
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.length < 4 || GREETING.containsMatchIn(line)) continue
            return line.take(120)
        }
        return text.lineSequence().firstOrNull { it.isNotBlank() }?.take(120) ?: "Note"
    }

    /** Short body preview for the card, greeting lines removed. */
    fun snippet(text: String, title: String): String? {
        val body = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !GREETING.containsMatchIn(it) && it != title.trim() }
            .joinToString(" ")
        return body.take(200).ifBlank { null }
    }

    fun schoolDays(child: Child): List<String> {
        val days = WEEKDAY_KEYS.filter { child.timetable.containsKey(it) }
        return days.ifEmpty { DEFAULT_SCHOOL_DAYS }
    }

    fun nextSchoolDay(after: LocalDate, schoolDays: List<String>): LocalDate {
        for (i in 1..7) {
            val d = after.plusDays(i.toLong())
            if (WEEKDAY_KEYS[d.dayOfWeek.value - 1] in schoolDays) return d
        }
        return after.plusDays(1)
    }

    private fun postedDate(post: Post): LocalDate? = try {
        Instant.parse(post.postedAt).atZone(ZoneId.systemDefault()).toLocalDate()
    } catch (e: Exception) {
        null
    }

    private fun findDue(body: String, posted: LocalDate?, schoolDays: List<String>): String? {
        DATE_NUM.find(body)?.let { m ->
            val (d, mo, yRaw) = m.destructured
            val y = yRaw.toInt().let { if (it < 100) it + 2000 else it }
            try {
                return LocalDate.of(y, mo.toInt(), d.toInt()).toString()
            } catch (e: Exception) { /* not a real date */ }
        }
        DATE_WORD.find(body)?.let { m ->
            val (d, monName, y) = m.destructured
            MONTHS[monName.lowercase().take(3)]?.let { mo ->
                try {
                    return LocalDate.of(y.toInt(), mo, d.toInt()).toString()
                } catch (e: Exception) { /* not a real date */ }
            }
        }
        if (posted != null && TOMORROW.containsMatchIn(body)) {
            return nextSchoolDay(posted, schoolDays).toString()
        }
        return null
    }

    fun fromPost(post: Post, child: Child): List<ActionItem> {
        val body = post.title + "\n" + post.body
        val isHoliday = HOLIDAY.containsMatchIn(body)
        if (!ACTION.containsMatchIn(body) && !isHoliday) return emptyList()

        val today = LocalDate.now()
        val posted = postedDate(post)
        // Old posts create noise, not action: skip anything posted > 30 days ago.
        if (posted != null && posted.isBefore(today.minusDays(30))) return emptyList()

        val due = findDue(body, posted, schoolDays(child))
        // Already stale when it arrives -> not actionable, don't create it.
        if (due != null && LocalDate.parse(due).isBefore(today.minusDays(3))) return emptyList()

        val amount = AMOUNT.find(body)?.groupValues?.get(1)?.let { "₹$it" }
        val checklist = BULLET.findAll(body).take(8)
            .map { ChecklistEntry(it.groupValues[1].trim()) }.toMutableList()
        if (due == null && amount == null && checklist.isEmpty() && !isHoliday) return emptyList()

        val category = when {
            isHoliday -> "holiday"
            amount != null -> "fee"
            Regex("\\b(test|exam)\\b", RegexOption.IGNORE_CASE).containsMatchIn(body) -> "test"
            else -> "task"
        }
        val title = headline(body)
        return listOf(ActionItem(
            id = post.key + "#0",
            childEmail = post.childEmail,
            postKey = post.key,
            title = title,
            detail = snippet(post.body, title),
            dueDate = due, amount = amount, category = category,
            checklist = checklist))
    }

    private val DAY_TOKENS = buildMap {
        for ((i, key) in WEEKDAY_KEYS.withIndex()) {
            put(key, key)
            put(key.take(2), key)
            put(java.time.DayOfWeek.of(i + 1).getDisplayName(
                TextStyle.FULL, Locale.ENGLISH).lowercase(), key)
        }
    }

    /**
     * Best-effort parse of an OCR'd timetable image into day -> periods.
     * Grid OCR is imperfect; the result is meant to be reviewed in Settings.
     * Returns null when fewer than 3 day rows were recognised.
     */
    fun parseTimetableText(text: String): Map<String, List<String>>? {
        val result = linkedMapOf<String, MutableList<String>>()
        var current: String? = null
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val head = line.split(Regex("[:\\s]+"), limit = 2)
            val day = DAY_TOKENS[head[0].lowercase().trimEnd('.', ',')]
            if (day != null) {
                current = day
                result.getOrPut(day) { mutableListOf() }
                if (head.size > 1) {
                    result[day]!!.addAll(head[1].split(Regex("[,|]"))
                        .map { it.trim() }.filter { it.isNotEmpty() && it.length <= 16 })
                }
            } else if (current != null && line.length <= 16 &&
                       !line.contains(Regex("\\d{2}[:.]\\d{2}"))) {
                result[current]!!.add(line)
            }
        }
        val cleaned = result.filterValues { it.isNotEmpty() }
            .mapValues { it.value.take(9) }
        return if (cleaned.keys.size >= 3) cleaned else null
    }
}
