package app.penny

import app.penny.ui.Format
import org.junit.Assert.assertEquals
import org.junit.Test

class AmountInputTest {
    @Test
    fun leadingSeparatorGetsZeroWithLanguageSeparator() {
        assertEquals("0,87", Format.withLeadingZero(".87", separator = ','))
        assertEquals("0,87", Format.withLeadingZero(",87", separator = ','))
        assertEquals("0.87", Format.withLeadingZero(",87", separator = '.'))
        assertEquals("0,", Format.withLeadingZero(",", separator = ','))
    }

    @Test
    fun otherInputIsKept() {
        assertEquals("", Format.withLeadingZero("", separator = ','))
        assertEquals("12.5", Format.withLeadingZero("12.5", separator = ','))
        assertEquals("0,5", Format.withLeadingZero("0,5", separator = '.'))
    }
}
