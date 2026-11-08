package com.example.checkin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@Composable
fun LineChart(values: List<Double?>, min: Double, max: Double, modifier: Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val padX = 12f
        val padY = 12f
        val w = size.width
        val h = size.height
        val n = values.size
        fun px(i: Int) = if (n <= 1) padX else padX + (w - 2 * padX) * i / (n - 1)
        fun py(v: Double) = padY + (h - 2 * padY) * (1f - ((v - min) / (max - min)).toFloat())

        for (k in 0..4) {
            val yy = padY + (h - 2 * padY) * k / 4f
            drawLine(grid, Offset(0f, yy), Offset(w, yy), 1f)
        }

        val segs = mutableListOf<MutableList<Int>>()
        var cur: MutableList<Int>? = null
        for (i in values.indices) {
            if (values[i] == null) {
                cur = null
            } else {
                val c = cur ?: mutableListOf<Int>().also { segs.add(it) }
                c.add(i)
                cur = c
            }
        }
        for (s in segs) {
            if (s.size < 2) continue
            val path = Path()
            val fill = Path()
            s.forEachIndexed { j, i ->
                val x = px(i)
                val y = py(values[i]!!)
                if (j == 0) {
                    path.moveTo(x, y); fill.moveTo(x, h - padY); fill.lineTo(x, y)
                } else {
                    path.lineTo(x, y); fill.lineTo(x, y)
                }
            }
            fill.lineTo(px(s.last()), h - padY)
            fill.close()
            drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = 0.28f), Color.Transparent)))
            drawPath(path, line, style = Stroke(width = 5f))
        }
        for (i in values.indices) {
            val v = values[i]
            if (v != null) drawCircle(line, 7f, Offset(px(i), py(v)))
        }
    }
}

/** Scatter of one metric (x) against another (y) over raw records, no jitter. */
@Composable
fun ScatterChart(points: List<Pair<Double, Double>>, xMin: Double, xMax: Double, yMin: Double, yMax: Double, modifier: Modifier) {
    val dot = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val padX = 20f
        val padY = 20f
        val w = size.width
        val h = size.height
        fun px(v: Double) = padX + (w - 2 * padX) * ((v - xMin) / (xMax - xMin)).toFloat().coerceIn(0f, 1f)
        fun py(v: Double) = padY + (h - 2 * padY) * (1f - ((v - yMin) / (yMax - yMin)).toFloat().coerceIn(0f, 1f))
        drawLine(grid, Offset(padX, h - padY), Offset(w - padX, h - padY), 1f)
        drawLine(grid, Offset(padX, padY), Offset(padX, h - padY), 1f)
        points.forEach { (x, y) ->
            drawCircle(dot.copy(alpha = 0.6f), 9f, Offset(px(x), py(y)))
        }
    }
}

/**
 * Calendar heatmap: last [weeks] weeks, one cell per day, colored by the day's average mood.
 * Grey - no data, red..green otherwise.
 */

/** Monday of the first of [weeks] week rows, so that the LAST row is the week containing [today]. */
internal fun heatmapStart(today: LocalDate, weeks: Int): LocalDate {
    var monday = today
    while (monday.dayOfWeek != java.time.DayOfWeek.MONDAY) monday = monday.minusDays(1)
    return monday.minusWeeks((weeks - 1).toLong())
}

@Composable
fun MoodHeatmap(daily: List<DayEntry>, weeks: Int, moodMetric: Metric?, modifier: Modifier) {
    val byDate = daily.associateBy { it.date }
    val today = LocalDate.now()
    val start = heatmapStart(today, weeks)
    // Theme colors are @Composable reads: take them here, not inside the plain lambda below.
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant
    val primaryColor = MaterialTheme.colorScheme.primary
    val cellColor: (DayEntry?) -> Color = { e ->
        val v = e?.metrics?.get("mood")
        if (v == null || moodMetric == null) emptyColor
        else colorFor(moodMetric, v, primaryColor)
    }
    androidx.compose.foundation.layout.Column(modifier = modifier, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp)) {
        for (wk in 0 until weeks) {
            androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp)) {
                for (dow in 0 until 7) {
                    val d = start.plusDays((wk * 7 + dow).toLong())
                    val e = byDate[d.toString()]
                    val color = if (d.isAfter(today)) Color.Transparent else cellColor(e)
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(color)
                    )
                }
            }
        }
    }
}
