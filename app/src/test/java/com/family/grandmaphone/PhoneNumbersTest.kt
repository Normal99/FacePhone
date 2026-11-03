package com.family.grandmaphone

import com.family.grandmaphone.data.CallRoute
import com.family.grandmaphone.data.PhoneNumbers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumbersTest {
    @Test
    fun sameLineInDifferentFormatsMatches() {
        assertTrue(PhoneNumbers.same("+90 532 123 45 67", "05321234567"))
        assertTrue(PhoneNumbers.same("00905321234567", "(0532) 123-45-67"))
        assertTrue(PhoneNumbers.same("+447700900123", "07700 900123"))
    }

    @Test
    fun differentLinesDoNotMatch() {
        assertFalse(PhoneNumbers.same("05321234567", "05321234568"))
        assertFalse(PhoneNumbers.same("", "05321234567"))
        assertFalse(PhoneNumbers.same("", ""))
    }

    @Test
    fun shortCodesOnlyMatchExactly() {
        assertTrue(PhoneNumbers.same("112", "112"))
        assertFalse(PhoneNumbers.same("112", "05321234112"))
    }

    @Test
    fun homeCountryNumbersAreRegularCalls() {
        for (number in listOf("91234567", "912 34 567", "+47 91 23 45 67", "004791234567", "112")) {
            assertEquals(number, CallRoute.PHONE, CallRoute.of(number, "+47"))
        }
    }

    @Test
    fun foreignNumbersUseWhatsApp() {
        for (number in listOf("+90 532 123 45 67", "00905321234567", "+447700900123")) {
            assertEquals(number, CallRoute.WHATSAPP, CallRoute.of(number, "+47"))
        }
    }

    @Test
    fun changingTheHomeCountryChangesTheRoute() {
        assertEquals(CallRoute.PHONE, CallRoute.of("+90 532 123 45 67", "+90"))
        assertEquals(CallRoute.WHATSAPP, CallRoute.of("+47 91 23 45 67", "+90"))
    }

    @Test
    fun noHomeCountryMeansRegularCalls() {
        assertEquals(CallRoute.PHONE, CallRoute.of("+90 532 123 45 67", ""))
    }

    @Test
    fun peopleOnlyInWhatsAppUseWhatsApp() {
        assertEquals(CallRoute.WHATSAPP, CallRoute.of("+47 91 23 45 67", "+47", fromWhatsAppOnly = true))
        assertEquals(CallRoute.WHATSAPP, CallRoute.of("+47 91 23 45 67", "", fromWhatsAppOnly = true))
    }

    @Test
    fun internationalForm() {
        assertEquals("905321234567", PhoneNumbers.international("+90 532 123 45 67", ""))
        assertEquals("905321234567", PhoneNumbers.international("00905321234567", ""))
        assertEquals("905321234567", PhoneNumbers.international("0532 123 45 67", "+90"))
        assertNull(PhoneNumbers.international("91234567", ""))
        assertNull(PhoneNumbers.international("", "+90"))
    }

    @Test
    fun turkishMobilesWrittenLocallyAreTurkish() {
        assertEquals("905321234567", PhoneNumbers.international("0532 123 45 67", "+47"))
        assertEquals("905321234567", PhoneNumbers.international("05321234567", ""))
        assertEquals(CallRoute.WHATSAPP, CallRoute.of("0532 123 45 67", "+47"))
        assertEquals(CallRoute.PHONE, CallRoute.of("0532 123 45 67", "+90"))
        // Norwegian numbers are unchanged.
        assertEquals(CallRoute.PHONE, CallRoute.of("91 23 45 67", "+47"))
    }
}
