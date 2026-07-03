package com.parentops.app

import kotlin.math.abs

/**
 * Reconstructs a timetable grid from OCR lines using their positions on the
 * image, instead of relying on reading order (which scrambles tables):
 *
 *  1. Find the day labels (Mo/Tue/Wednesday…) — they anchor each row.
 *  2. Assign every other text line to the nearest day row by vertical centre.
 *  3. Order a row's entries left-to-right; cluster fragments that belong to
 *     the same cell (e.g. "PT" / "Swim" / "[B]" stacked in one narrow cell).
 *  4. Skip timings ("9:00-9:40"), tall merged cells (Break/Lunch spanning all
 *     rows) and header text that sits above the first day row.
 */
data class OcrLine(val text: String, val left: Int, val top: Int,
                   val right: Int, val bottom: Int) {
    val cy get() = (top + bottom) / 2
    val cx get() = (left + right) / 2
    val h get() = bottom - top
}

object TimetableOcr {

    private fun median(values: List<Int>): Int =
        if (values.isEmpty()) 0 else values.sorted()[values.size / 2]

    fun parse(lines: List<OcrLine>): Map<String, List<String>>? {
        val labeled = lines.mapNotNull { l ->
            Extract.DAY_TOKENS[l.text.trim().lowercase().trimEnd('.', ',')]
                ?.let { day -> day to l }
        }
        // The day-label column is the leftmost occurrence of each day.
        val anchors = labeled.groupBy({ it.first }, { it.second })
            .mapValues { (_, ls) -> ls.minByOrNull { it.left }!! }
        if (anchors.size < 3) return null

        val ordered = anchors.entries.sortedBy { it.value.cy }
        val rowGaps = ordered.zipWithNext { a, b -> b.value.cy - a.value.cy }
        val rowGap = median(rowGaps).takeIf { it > 0 } ?: return null
        val medianH = median(lines.map { it.h }).coerceAtLeast(8)

        data class Entry(val cx: Int, val cy: Int, val text: String)
        val rows = linkedMapOf<String, MutableList<Entry>>()
        for (day in ordered.map { it.key }) rows[day] = mutableListOf()

        for (l in lines) {
            val t = l.text.trim()
            if (t.isEmpty() || t.length > 18) continue
            if (Extract.DAY_TOKENS.containsKey(t.lowercase().trimEnd('.', ','))) continue
            if (t.contains(Regex("\\d{1,2}[:.]\\d{2}"))) continue   // period timings
            if (l.h > medianH * 2.2) continue                       // merged Break/Lunch cell
            val nearest = ordered.minByOrNull { abs(it.value.cy - l.cy) } ?: continue
            if (abs(nearest.value.cy - l.cy) > rowGap * 0.45) continue  // header/footer text
            if (l.left <= nearest.value.right) continue             // in/left of label column
            rows[nearest.key]!!.add(Entry(l.cx, l.cy, t))
        }

        // Left-to-right; fragments in the same column become one period.
        val colTol = (medianH * 1.8).toInt()
        val grid = rows.mapValues { (_, entries) ->
            val sorted = entries.sortedBy { it.cx }
            val cells = mutableListOf<MutableList<Entry>>()
            for (e in sorted) {
                val cell = cells.lastOrNull()
                if (cell != null && abs(e.cx - cell.map { it.cx }.average()) <= colTol) {
                    cell.add(e)
                } else {
                    cells.add(mutableListOf(e))
                }
            }
            cells.map { cell -> cell.sortedBy { it.cy }.joinToString(" ") { it.text } }
                .take(10)
        }.filterValues { it.isNotEmpty() }

        return if (grid.size >= 3) grid else null
    }
}
