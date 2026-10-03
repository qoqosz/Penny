package app.penny.ui

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
    private val locale = Locale.forLanguageTag("pl-PL")
    private val zone get() = ZoneId.systemDefault()
    private val dayFormat = DateTimeFormatter.ofPattern("EEEE, d MMMM", locale)
    private val dayYearFormat = DateTimeFormatter.ofPattern("d MMMM yyyy", locale)
    private val shortFormat = DateTimeFormatter.ofPattern("d.MM.yyyy", locale)

    fun money(amount: BigDecimal, currency: String, signed: Boolean = false): String {
        val format = NumberFormat.getCurrencyInstance(locale)
        runCatching { format.currency = Currency.getInstance(currency) }
        format.maximumFractionDigits = 2
        format.minimumFractionDigits = 2
        val text = format.format(amount.setScale(2, RoundingMode.HALF_EVEN))
        return if (signed && amount.signum() > 0) "+$text" else text
    }

    fun money(amount: String, currency: String, signed: Boolean = false): String =
        money(amount.toBigDecimalOrNull() ?: BigDecimal.ZERO, currency, signed)

    fun localDate(instant: Instant): LocalDate = instant.atZone(zone).toLocalDate()

    fun dayHeader(date: LocalDate): String {
        val today = LocalDate.now(zone)
        return when (date) {
            today -> "Dzisiaj"
            today.minusDays(1) -> "Wczoraj"
            else -> if (date.year == today.year) date.format(dayFormat).replaceFirstChar { it.uppercase() }
            else date.format(dayYearFormat)
        }
    }

    fun shortDate(date: LocalDate): String = date.format(shortFormat)

    /** Accepts "12,50", "12.50", "1 234,5". Returns null for anything that isn't a positive amount. */
    fun parseAmount(text: String): BigDecimal? {
        val normalized = text.replace(" ", "").replace(" ", "").replace(',', '.')
        if (!Regex("""\d+(\.\d{1,2})?""").matches(normalized)) return null
        return normalized.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
    }
}
