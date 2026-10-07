package app.penny.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.penny.R
import app.penny.data.CategoryTotal
import app.penny.data.PeriodType
import app.penny.data.Report
import app.penny.data.Repository
import app.penny.data.SyncStatus
import java.math.BigDecimal
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/** The Report tab: a month or a year of net worth, then income and expenses by category. */
@Composable
fun ReportList(repository: Repository, vm: ReportViewModel) {
    val app by repository.state.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val report = state.report
    var showIncome by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        item(key = "controls") {
            PeriodControls(state, onType = vm::setType, onPrevious = vm::previous, onNext = vm::next)
        }
        if (report != null && (state.currencies.size > 1 || report.leftOut.isNotEmpty())) {
            item(key = "currencies") { CurrencyChips(state.currencies, report, vm::setCurrency) }
        }
        if (report == null) {
            item(key = "empty") {
                if (app.status == SyncStatus.Syncing) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    EmptyState(stringResource(R.string.report_empty))
                }
            }
            return@LazyColumn
        }
        item(key = "net-worth") { NetWorthCard(report) }
        item(key = "breakdown") { BreakdownCard(report, showIncome, onShowIncome = { showIncome = it }, repository) }
        categorySection(
            "income", report.income, report.currency, R.string.report_income, R.string.report_no_income, repository,
        )
        categorySection(
            "expenses", report.expenses, report.currency, R.string.report_expenses, R.string.report_no_expenses, repository,
        )
    }
}

@Composable
private fun PeriodControls(state: ReportState, onType: (PeriodType) -> Unit, onPrevious: () -> Unit, onNext: () -> Unit) {
    val period = state.period
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(PeriodType.MONTH to R.string.report_monthly, PeriodType.YEAR to R.string.report_yearly)
                .forEachIndexed { index, (type, label) ->
                    SegmentedButton(
                        selected = period.type == type,
                        onClick = { onType(type) },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                    ) { Text(stringResource(label)) }
                }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevious, enabled = state.canGoBack) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = stringResource(R.string.report_previous))
            }
            Text(
                if (period.type == PeriodType.MONTH) Format.month(YearMonth.of(period.year, period.month))
                else period.year.toString(),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onNext, enabled = state.canGoForward) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = stringResource(R.string.report_next))
            }
        }
    }
}

@Composable
private fun CurrencyChips(currencies: List<String>, report: Report, onSelect: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        if (currencies.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                currencies.forEach { currency ->
                    FilterChip(selected = currency == report.currency, onClick = { onSelect(currency) }, label = { Text(currency) })
                }
            }
        }
        val note = when {
            report.leftOut.isNotEmpty() -> stringResource(R.string.report_left_out, report.leftOut.joinToString(", "))
            report.converted -> stringResource(R.string.report_converted)
            else -> null
        }
        if (note != null) {
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ReportCard(content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun NetWorthCard(report: Report) {
    // Touching the chart shows that day's value in the header.
    var selected by remember(report) { mutableStateOf<Int?>(null) }
    val point = selected?.let { report.netWorth.getOrNull(it) }
    val change = report.endValue - report.startValue
    ReportCard {
        Text(stringResource(R.string.report_net_worth), style = MaterialTheme.typography.labelLarge)
        Text(
            Format.money(point?.value ?: report.endValue, report.currency),
            style = MaterialTheme.typography.headlineSmall,
        )
        if (point != null) {
            Text(
                Format.date(point.date.atStartOfDay(ZoneId.systemDefault()).toInstant(), "EEEEdMMMMyyyy"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                stringResource(R.string.report_change, Format.money(change, report.currency, signed = true)),
                style = MaterialTheme.typography.bodySmall,
                color = if (change.signum() > 0) AmountColors.income else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        NetWorthChart(report, selected, onSelect = { selected = it })
        val note = when {
            report.hasInvestments -> R.string.totals_investments_note
            report.unvalued -> R.string.report_unvalued
            report.hasSecurities -> R.string.report_securities
            else -> null
        }
        if (note != null) {
            Text(
                stringResource(note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * An area chart over the whole period (a month in progress ends at today), scaled to the period's range like Money's,
 * with the highest and lowest values at the right.
 */
@Composable
private fun NetWorthChart(report: Report, selected: Int?, onSelect: (Int?) -> Unit) {
    val points = report.netWorth
    if (points.isEmpty()) return
    val days = ChronoUnit.DAYS.between(report.period.start, report.period.endExclusive).toInt()
    val values = points.map { it.value.toFloat() }
    val max = values.max()
    val min = values.min()
    val span = (max - min).takeIf { it > 0f } ?: maxOf(abs(max), 1f)
    val top = max + span * 0.08f
    val bottom = min - span * 0.08f

    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val ring = MaterialTheme.colorScheme.surfaceContainerHigh
    val labelStyle = MaterialTheme.typography.labelSmall
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val description = stringResource(R.string.report_net_worth_chart, Format.money(report.startValue, report.currency),
        Format.money(report.endValue, report.currency))

    Column {
        Text(
            Format.wholeMoney(BigDecimal(max.toDouble()), report.currency), style = labelStyle, color = labelColor,
            modifier = Modifier.align(Alignment.End),
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .semantics { contentDescription = description }
                .pointerInput(report) {
                    fun indexAt(x: Float): Int {
                        val step = size.width.toFloat() / (days - 1).coerceAtLeast(1)
                        return (x / step).roundToInt().coerceIn(0, points.size - 1)
                    }
                    // Press or drag sideways to see a day's value; a vertical drag is left to the list's scrolling.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        onSelect(indexAt(down.position.x))
                        var dragging = false
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val dx = abs(change.position.x - down.position.x)
                            val dy = abs(change.position.y - down.position.y)
                            if (!dragging && dy > viewConfiguration.touchSlop && dy > dx) break
                            if (dx > viewConfiguration.touchSlop) dragging = true
                            if (dragging) change.consume()
                            onSelect(indexAt(change.position.x))
                        }
                        onSelect(null)
                    }
                },
        ) {
            val step = size.width / (days - 1).coerceAtLeast(1)
            fun x(i: Int) = if (points.size == 1 && days == 1) size.width / 2 else i * step
            fun y(v: Float) = size.height * (top - v) / (top - bottom)

            if (bottom < 0f && top > 0f) {
                drawLine(
                    grid, Offset(0f, y(0f)), Offset(size.width, y(0f)), strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                )
            }
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())

            val path = Path().apply {
                values.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) }
            }
            val area = Path().apply {
                addPath(path)
                lineTo(x(values.lastIndex), size.height)
                lineTo(x(0), size.height)
                close()
            }
            drawPath(area, Brush.verticalGradient(listOf(line.copy(alpha = 0.35f), line.copy(alpha = 0.05f))))
            drawPath(path, line, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            if (values.size == 1) drawCircle(line, 4.dp.toPx(), Offset(x(0), y(values[0])))

            selected?.let { i ->
                val at = Offset(x(i), y(values[i]))
                drawLine(grid, Offset(at.x, 0f), Offset(at.x, size.height), strokeWidth = 1.dp.toPx())
                drawCircle(ring, 6.dp.toPx(), at)
                drawCircle(line, 4.dp.toPx(), at)
            }
        }
        Text(
            Format.wholeMoney(BigDecimal(min.toDouble()), report.currency), style = labelStyle, color = labelColor,
            modifier = Modifier.align(Alignment.End),
        )
        Row(Modifier.fillMaxWidth()) {
            val zone = ZoneId.systemDefault()
            val skeleton = if (report.period.type == PeriodType.MONTH) "dMMM" else "MMM"
            Text(
                Format.date(report.period.start.atStartOfDay(zone).toInstant(), skeleton),
                style = labelStyle, color = labelColor, modifier = Modifier.weight(1f),
            )
            Text(
                Format.date(report.period.endExclusive.minusDays(1).atStartOfDay(zone).toInstant(), skeleton),
                style = labelStyle, color = labelColor,
            )
        }
    }
}

private data class Slice(val name: String, val amount: BigDecimal, val color: Color)

/**
 * The pie chart's slices: the largest categories in the palette's order, the rest folded into "Other".
 * The lists below use the same colors ([sliceColor]).
 */
@Composable
private fun slices(categories: List<CategoryTotal>): List<Slice> {
    val shown = if (categories.size <= ChartColors.categorical.size) categories.size else ChartColors.categorical.size - 1
    val rest = categories.drop(shown)
    return categories.take(shown).mapIndexed { i, c -> Slice(c.displayName(), c.amount, sliceColor(i, categories.size)) } +
        if (rest.isEmpty()) emptyList()
        else listOf(Slice(stringResource(R.string.report_other), rest.fold(BigDecimal.ZERO) { s, c -> s + c.amount }, ChartColors.other))
}

@Composable
private fun sliceColor(rank: Int, count: Int): Color {
    val palette = ChartColors.categorical
    return if (count <= palette.size || rank < palette.size - 1) palette[rank] else ChartColors.other
}

@Composable
private fun BreakdownCard(report: Report, showIncome: Boolean, onShowIncome: (Boolean) -> Unit, repository: Repository) {
    val categories = if (showIncome) report.income else report.expenses
    val total = if (showIncome) report.totalIncome else report.totalExpenses
    ReportCard {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(false to R.string.report_expenses, true to R.string.report_income).forEachIndexed { index, (income, label) ->
                SegmentedButton(
                    selected = showIncome == income,
                    onClick = { onShowIncome(income) },
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                ) { Text(stringResource(label)) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(if (showIncome) R.string.report_income_by_category else R.string.report_expenses_by_category),
            style = MaterialTheme.typography.labelLarge,
        )
        Text(Format.money(total, report.currency), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        if (categories.isEmpty()) {
            EmptyState(stringResource(if (showIncome) R.string.report_no_income else R.string.report_no_expenses))
            return@ReportCard
        }
        val slices = slices(categories)
        Row(verticalAlignment = Alignment.CenterVertically) {
            PieChart(slices, Modifier.size(150.dp))
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                slices.forEach { slice ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(slice.color, RoundedCornerShape(2.dp)))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            slice.name, style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            Format.percent(slice.amount, total), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PieChart(slices: List<Slice>, modifier: Modifier) {
    val gap = MaterialTheme.colorScheme.surfaceContainerHigh
    val total = slices.fold(BigDecimal.ZERO) { s, it -> s + it.amount }.toFloat()
    Canvas(modifier) {
        val diameter = size.minDimension
        val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
        val center = Offset(size.width / 2, size.height / 2)
        val radius = diameter / 2
        var angle = -90f
        val boundaries = ArrayList<Float>()
        slices.forEach { slice ->
            val sweep = 360f * slice.amount.toFloat() / total
            drawArc(slice.color, angle, sweep, useCenter = true, topLeft = topLeft, size = Size(diameter, diameter))
            boundaries += angle
            angle += sweep
        }
        // A thin gap in the card's color between slices.
        if (slices.size > 1) boundaries.forEach { a ->
            val rad = Math.toRadians(a.toDouble())
            val edge = Offset(center.x + radius * kotlin.math.cos(rad).toFloat(), center.y + radius * kotlin.math.sin(rad).toFloat())
            drawLine(gap, center, edge, strokeWidth = 2.dp.toPx())
        }
    }
}

private fun LazyListScope.categorySection(
    key: String,
    categories: List<CategoryTotal>,
    currency: String,
    title: Int,
    empty: Int,
    repository: Repository,
) {
    item(key = "$key-header") {
        SectionHeader(
            stringResource(title),
            Format.money(categories.fold(BigDecimal.ZERO) { s, c -> s + c.amount }, currency),
        )
    }
    if (categories.isEmpty()) {
        item(key = "$key-empty") { EmptyState(stringResource(empty)) }
        return
    }
    val total = categories.fold(BigDecimal.ZERO) { s, c -> s + c.amount }
    items(categories.withIndex().toList(), key = { "$key-${it.value.categoryId}" }) { (index, category) ->
        CategoryRow(category, sliceColor(index, categories.size), total, currency, repository)
    }
}

/** A category with its share and color from the pie chart; tapping shows its subcategories. */
@Composable
private fun CategoryRow(category: CategoryTotal, color: Color, total: BigDecimal, currency: String, repository: Repository) {
    var expanded by rememberSaveable(category.categoryId) { mutableStateOf(false) }
    val snapshot = repository.state.collectAsStateWithLifecycle().value.snapshot
    val glyph = rememberMoneyIcon(repository.icons, snapshot?.category(category.categoryId)?.iconId)
    Column {
        ListRow(
            title = category.displayName(),
            subtitle = Format.percent(category.amount, total),
            modifier = if (category.parts.isNotEmpty()) Modifier.clickable { expanded = !expanded } else Modifier,
            leading = {
                Box(Modifier.size(32.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
                    if (glyph != null) Icon(glyph, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Format.money(category.amount, currency), style = MaterialTheme.typography.bodyLarge)
                    if (category.parts.isNotEmpty()) {
                        Icon(
                            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp).size(20.dp),
                        )
                    }
                }
            },
        )
        if (expanded) category.parts.forEach { part ->
            Row(
                Modifier.fillMaxWidth().padding(start = 60.dp, end = 40.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    part.displayName(), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    Format.money(part.amount, currency), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CategoryTotal.displayName(): String = name ?: stringResource(R.string.report_uncategorized)
