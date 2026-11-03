package com.family.grandmaphone

import com.family.grandmaphone.data.Contact
import com.family.grandmaphone.data.ContactMatch
import com.family.grandmaphone.data.MessageKind
import com.family.grandmaphone.mms.MmsRouting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoutingTest {
    private val ali = Contact("1", "Ali", "+90 532 123 45 67", usesWhatsApp = true)
    private val ayse = Contact("2", "Ayşe", "0533 765 43 21", usesWhatsApp = false)
    private val contacts = listOf(ali, ayse)

    @Test
    fun numbersFindTheRightPerson() {
        assertEquals(ali, ContactMatch.byNumber(contacts, "05321234567"))
        assertEquals(ayse, ContactMatch.byNumber(contacts, "+905337654321"))
    }

    @Test
    fun strangersAndHiddenNumbersMatchNobody() {
        assertNull(ContactMatch.byNumber(contacts, "05001112233"))
        assertNull(ContactMatch.byNumber(contacts, ""))
        assertNull(ContactMatch.byNumber(contacts, null))
    }

    @Test
    fun whatsAppTitlesMatchByNameOrNumber() {
        assertEquals(ali, ContactMatch.byWhatsAppTitle(contacts, " ali "))
        assertEquals(ali, ContactMatch.byWhatsAppTitle(contacts, "+90 532 123 45 67"))
        assertNull(ContactMatch.byWhatsAppTitle(contacts, "Family group"))
        assertNull(ContactMatch.byWhatsAppTitle(contacts, null))
    }

    @Test
    fun aWhatsAppNotificationMatchesPeopleNotMarkedAsUsingIt() {
        val ayse = Contact("x", "Ayşe", "0532 000 00 01")
        assertEquals(ayse, ContactMatch.byWhatsAppTitle(listOf(ayse), "Ayşe"))
        assertEquals(ayse, ContactMatch.byWhatsAppTitle(listOf(ayse), "+90 532 000 00 01"))
    }

    @Test
    fun whatsAppPrefersThePersonMarkedAsUsingIt() {
        val other = Contact("x", "Ayşe", "1")
        val marked = Contact("y", "Ayşe", "2", usesWhatsApp = true)
        assertEquals(marked, ContactMatch.byWhatsAppTitle(listOf(other, marked), "Ayşe"))
    }

    @Test
    fun mmsPartsAreClassified() {
        assertEquals(MessageKind.VOICE, MmsRouting.kindOf("audio/amr"))
        assertEquals(MessageKind.PICTURE, MmsRouting.kindOf("IMAGE/JPEG"))
        assertEquals(MessageKind.VIDEO, MmsRouting.kindOf("video/3gpp"))
        assertEquals(MessageKind.TEXT, MmsRouting.kindOf("text/plain"))
        assertNull(MmsRouting.kindOf("application/smil"))
    }

    @Test
    fun unknownTypesGetAHarmlessExtension() {
        assertEquals("amr", MmsRouting.extensionOf("audio/amr"))
        assertEquals("bin", MmsRouting.extensionOf("../../evil"))
    }

    @Test
    fun voiceLengthFollowsCarrierLimit() {
        assertEquals(120, MmsRouting.maxVoiceSeconds(300 * 1024))
        assertEquals(66, MmsRouting.maxVoiceSeconds(100 * 1024))
        assertEquals(15, MmsRouting.maxVoiceSeconds(10 * 1024))
    }
}
