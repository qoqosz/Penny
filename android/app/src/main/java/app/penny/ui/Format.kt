package app.penny.ui

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.penny.R
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

object Format {
    private val zone get() = ZoneId.systemDefault()

    /** Uses the default locale, which follows the language chosen in Penny's settings. */
    fun money(amount: BigDecimal, currency: String, signed: Boolean = false): String {
        val format = NumberFormat.getCurrencyInstance(Locale.getDefault())
        runCatching { format.currency = Currency.getInstance(currency) }
        format.maximumFractionDigits = 2
        format.minimumFractionDigits = 2
        val text = format.format(amount.setScale(2, RoundingMode.HALF_EVEN))
        return if (signed && amount.signum() > 0) "+$text" else text
    }

    fun money(amount: String, currency: String, signed: Boolean = false): String =
        money(amount.toBigDecimalOrNull() ?: BigDecimal.ZERO, currency, signed)

    /** Like [money] without the fraction, for chart labels. */
    fun wholeMoney(amount: BigDecimal, currency: String): String {
        val format = NumberFormat.getCurrencyInstance(Locale.getDefault())
        runCatching { format.currency = Currency.getInstance(currency) }
        format.maximumFractionDigits = 0
        format.minimumFractionDigits = 0
        return format.format(amount.setScale(0, RoundingMode.HALF_EVEN))
    }

    /** [part] as a share of [whole], e.g. "23%" ("23 %" in Polish), or "<1%". */
    fun percent(part: BigDecimal, whole: BigDecimal): String {
        val share = if (whole.signum() == 0) 0.0 else part.toDouble() / whole.toDouble()
        val format = NumberFormat.getPercentInstance(Locale.getDefault())
        // A tiny share isn't nothing.
        return if (share > 0 && share < 0.005) "<" + format.format(0.01) else format.format(share)
    }

    /** An exchange rate: like [money], with up to 6 decimals. */
    fun rate(rate: BigDecimal, currency: String): String {
        val format = NumberFormat.getCurrencyInstance(Locale.getDefault())
        runCatching { format.currency = Currency.getInstance(currency) }
        format.minimumFractionDigits = 2
        format.maximumFractionDigits = 6
        return format.format(rate)
    }

    fun localDate(instant: Instant): LocalDate = instant.atZone(zone).toLocalDate()

    @Composable
    fun dayHeader(date: LocalDate): String {
        val locale = LocalConfiguration.current.locales[0]
        val today = LocalDate.now(zone)
        return when (date) {
            today -> stringResource(R.string.today)
            today.minusDays(1) -> stringResource(R.string.yesterday)
            else -> {
                val skeleton = if (date.year == today.year) "EEEEdMMMM" else "dMMMMyyyy"
                val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
                date.format(DateTimeFormatter.ofPattern(pattern, locale)).replaceFirstChar { it.titlecase(locale) }
            }
        }
    }

    fun coordinates(location: app.penny.data.Location): String =
        String.format(Locale.US, "%.5f, %.5f", location.latitude ?: 0.0, location.longitude ?: 0.0)

    /** The full date and time, e.g. "Tuesday, 6 October 2026 at 12:36", with the phone's 12/24-hour setting. */
    @Composable
    fun dateTime(instant: Instant): String {
        val locale = LocalConfiguration.current.locales[0]
        val time = if (DateFormat.is24HourFormat(LocalContext.current)) "Hm" else "hm"
        val pattern = DateFormat.getBestDateTimePattern(locale, "EEEEdMMMMyyyy$time")
        return instant.atZone(zone).format(DateTimeFormatter.ofPattern(pattern, locale)).replaceFirstChar { it.titlecase(locale) }
    }

    @Composable
    fun shortDate(instant: Instant): String = date(instant, "dMMMyyyy")

    /** [instant]'s local date in the app language's best pattern for [skeleton] (see `DateFormat.getBestDateTimePattern`). */
    @Composable
    fun date(instant: Instant, skeleton: String): String {
        val locale = LocalConfiguration.current.locales[0]
        val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
        return instant.atZone(zone).format(DateTimeFormatter.ofPattern(pattern, locale)).replaceFirstChar { it.titlecase(locale) }
    }

    /** E.g. "September 2026" (the nominative form in Polish: "Wrzesień 2026"). */
    @Composable
    fun month(month: YearMonth): String {
        val locale = LocalConfiguration.current.locales[0]
        val pattern = DateFormat.getBestDateTimePattern(locale, "MMMMyyyy")
        return month.format(DateTimeFormatter.ofPattern(pattern, locale)).replaceFirstChar { it.titlecase(locale) }
    }

    /** Accepts "12,50", "12.50", "1 234,5". Returns null for anything that isn't a positive amount. */
    fun parseAmount(text: String): BigDecimal? {
        val normalized = text.replace("\u00A0", "").replace(" ", "").replace(',', '.')
        if (!Regex("""\d+(\.\d{1,2})?""").matches(normalized)) return null
        return normalized.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
    }
}
