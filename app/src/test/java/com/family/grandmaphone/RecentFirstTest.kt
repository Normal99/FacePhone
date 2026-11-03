package com.family.grandmaphone

import com.family.grandmaphone.call.CallEntry
import com.family.grandmaphone.call.CallHistory
import com.family.grandmaphone.call.CallKind
import com.family.grandmaphone.data.Contact
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentFirstTest {
    private val ali = Contact("a", "Ali", "+4791234567")
    private val ayse = Contact("b", "Ayşe", "+4792345678")
    private val can = Contact("c", "Can", "+905321234567")

    @Test
    fun lastCalledComesFirst() {
        val calls = listOf(
            CallEntry("92345678", CallKind.MISSED, 300, 0),
            CallEntry("+4791234567", CallKind.OUTGOING, 100, 20),
        )
        assertEquals(listOf(ayse, ali, can), CallHistory.recentFirst(listOf(ali, ayse, can), calls, emptyMap()))
        // A WhatsApp call she started counts too.
        assertEquals(listOf(can, ayse, ali), CallHistory.recentFirst(listOf(ali, ayse, can), calls, mapOf("c" to 400L)))
        assertEquals(listOf(ali, ayse, can), CallHistory.recentFirst(listOf(ali, ayse, can), emptyList(), emptyMap()))
        assertEquals(true, com.family.grandmaphone.data.PhoneNumbers.same("92345678", "+47 923 45 678"))
    }
}
