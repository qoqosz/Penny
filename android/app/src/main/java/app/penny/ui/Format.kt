package app.penny.ui

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import app.penny.R
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
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

    /** Accepts "12,50", "12.50", "1 234,5". Returns null for anything that isn't a positive amount. */
    fun parseAmount(text: String): BigDecimal? {
        val normalized = text.replace("\u00A0", "").replace(" ", "").replace(',', '.')
        if (!Regex("""\d+(\.\d{1,2})?""").matches(normalized)) return null
        return normalized.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
    }
}
