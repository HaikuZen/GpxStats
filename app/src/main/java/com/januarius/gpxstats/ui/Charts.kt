package com.januarius.gpxstats.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Small hand-rolled bar (+ overlaid line) charts for the Statistics tab (plain
 * `Box`/`background` bars and a `Canvas` line — no charting library, so the dependency
 * graph and APK size don't change). Bars/lines carry color for scannability, but every
 * number they represent is also spelled out in the table underneath each chart, so
 * nothing here is the only copy of a value.
 */

/** One point on the period trend chart: distance drives the bar, elevation gain the line. */
data class PeriodPoint(val label: String, val distanceMeters: Double, val elevationGainMeters: Double)

/** A fixed, deterministic qualitative palette — cycled by a hash of the activity label. */
private val ActivityChartPalette = listOf(
    Color(0xFF1B5E20), // green
    Color(0xFF0277BD), // blue
    Color(0xFFEF6C00), // orange
    Color(0xFF6A1B9A), // purple
    Color(0xFF00695C), // teal
    Color(0xFFC62828), // red
    Color(0xFF9E9D24), // olive
    Color(0xFF4E342E)  // brown
)

/** Same label -> same color every time, independent of sort order. */
private fun activityColor(label: String): Color {
    val index = (label.lowercase().hashCode() and 0x7fffffff) % ActivityChartPalette.size
    return ActivityChartPalette[index]
}

/**
 * Horizontal bar per entry, length proportional to [valueOf]. Used for "distance by
 * activity" — a quick visual read of which activity dominates, on top of the exact
 * numbers in the table below it.
 *
 * When [secondaryOf] is given, a line connects a marker per row positioned along the
 * same track by that value's own fraction of its own max (independent scale from the
 * bars) — e.g. distance bars with an elevation-gain line, to spot activities that climb
 * disproportionately to how far they go.
 */
@Composable
fun ActivityBarChart(
    entries: List<ActivityStat>,
    valueOf: (ActivityStat) -> Double,
    valueLabel: (ActivityStat) -> String,
    secondaryOf: ((ActivityStat) -> Double)? = null,
    secondaryLabel: String = "",
    modifier: Modifier = Modifier
) {
    if (entries.isEmpty()) return
    val maxValue = entries.maxOf(valueOf).coerceAtLeast(0.0001)
    val maxSecondary = secondaryOf?.let { f -> entries.maxOf(f).coerceAtLeast(0.0001) }
    val lineColor = MaterialTheme.colorScheme.tertiary
    val density = LocalDensity.current

    val labelWidth = 72.dp
    val valueWidth = 60.dp
    val gap = 8.dp
    val rowHeight = 22.dp
    val rowSpacing = 8.dp

    Column(modifier.fillMaxWidth()) {
        if (secondaryOf != null) {
            LegendItem(color = lineColor, label = secondaryLabel, isLine = true)
            Spacer(Modifier.height(8.dp))
        }

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val trackWidth = (maxWidth - labelWidth - valueWidth - gap * 2).coerceAtLeast(0.dp)

            Column(verticalArrangement = Arrangement.spacedBy(rowSpacing)) {
                entries.forEach { stat ->
                    val fraction = (valueOf(stat) / maxValue).toFloat().coerceIn(0f, 1f)
                    Row(
                        modifier = Modifier.fillMaxWidth().height(rowHeight),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stat.label,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(labelWidth)
                        )
                        Spacer(Modifier.width(gap))
                        Box(
                            Modifier
                                .width(trackWidth)
                                .height(14.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            if (fraction > 0f) {
                                Box(
                                    Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(fraction.coerceAtLeast(0.03f))
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(activityColor(stat.label))
                                )
                            }
                        }
                        Spacer(Modifier.width(gap))
                        Text(
                            valueLabel(stat),
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(valueWidth)
                        )
                    }
                }
            }

            if (secondaryOf != null && maxSecondary != null) {
                val trackWidthPx = with(density) { trackWidth.toPx() }
                val originXPx = with(density) { (labelWidth + gap).toPx() }
                val rowHeightPx = with(density) { rowHeight.toPx() }
                val rowSpacingPx = with(density) { rowSpacing.toPx() }
                val points = entries.mapIndexed { index, stat ->
                    val xFraction = (secondaryOf(stat) / maxSecondary).toFloat().coerceIn(0f, 1f)
                    Offset(
                        x = originXPx + trackWidthPx * xFraction,
                        y = index * (rowHeightPx + rowSpacingPx) + rowHeightPx / 2f
                    )
                }
                Canvas(Modifier.matchParentSize()) { drawTrendLine(points, lineColor) }
            }
        }
    }
}

/**
 * Vertical distance bars with an overlaid elevation-gain line, one point per time
 * bucket. [entries] must already be in the order to display left-to-right
 * (chronological — oldest first) and pre-trimmed to a sensible count; this composable
 * does no sorting or capping of its own. Distance and elevation gain are scaled
 * independently (each to its own max) since their magnitudes aren't comparable.
 */
@Composable
fun PeriodBarChart(
    entries: List<PeriodPoint>,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 96.dp
) {
    if (entries.isEmpty()) return
    val maxDistance = entries.maxOf { it.distanceMeters }.coerceAtLeast(0.0001)
    val maxClimb = entries.maxOf { it.elevationGainMeters }.coerceAtLeast(0.0001)
    val barColor = MaterialTheme.colorScheme.primary
    val lineColor = MaterialTheme.colorScheme.tertiary
    val density = LocalDensity.current
    val labelStride = when {
        entries.size <= 6 -> 1
        entries.size <= 10 -> 2
        else -> 3
    }

    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LegendItem(color = barColor, label = "Distance", isLine = false)
            Spacer(Modifier.width(16.dp))
            LegendItem(color = lineColor, label = "Climb gain", isLine = true)
        }
        Spacer(Modifier.height(8.dp))

        BoxWithConstraints(Modifier.fillMaxWidth().height(chartHeight)) {
            val spacing = 4.dp
            val n = entries.size
            val totalWidthPx = with(density) { maxWidth.toPx() }
            val spacingPx = with(density) { spacing.toPx() }
            val colWidthPx = ((totalWidthPx - spacingPx * (n - 1)) / n).coerceAtLeast(1f)
            val chartHeightPx = with(density) { chartHeight.toPx() }

            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                entries.forEach { point ->
                    val fraction = (point.distanceMeters / maxDistance).toFloat().coerceIn(0f, 1f)
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                        if (fraction > 0f) {
                            Box(
                                Modifier
                                    .fillMaxWidth(0.6f)
                                    .fillMaxHeight(fraction.coerceAtLeast(0.03f))
                                    .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                    .background(barColor)
                            )
                        }
                    }
                }
            }

            val points = entries.mapIndexed { index, point ->
                val fraction = (point.elevationGainMeters / maxClimb).toFloat().coerceIn(0f, 1f)
                Offset(
                    x = index * colWidthPx + colWidthPx / 2f + index * spacingPx,
                    y = chartHeightPx * (1f - fraction)
                )
            }
            Canvas(Modifier.matchParentSize()) { drawTrendLine(points, lineColor) }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            entries.forEachIndexed { index, point ->
                Text(
                    text = if (index % labelStride == 0 || index == entries.lastIndex) point.label else "",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String, isLine: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(16.dp)
                .height(if (isLine) 3.dp else 10.dp)
                .clip(RoundedCornerShape(if (isLine) 50 else 3))
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/** Connects [points] with a stroked path and a small dot at each one. */
private fun DrawScope.drawTrendLine(points: List<Offset>, color: Color) {
    if (points.isEmpty()) return
    if (points.size >= 2) {
        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        drawPath(path, color = color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
    points.forEach { p -> drawCircle(color = color, radius = 3.dp.toPx(), center = p) }
}
