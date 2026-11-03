package com.family.grandmaphone.data

data class Contact(
    val id: String,
    val name: String,
    val phone: String,
    val usesWhatsApp: Boolean = false,
    val helpsInEmergency: Boolean = false,
    val starred: Boolean = false,
    val hidden: Boolean = false,
    val nickname: String = "",
    /** Extra names only the family's settings know, such as what she calls this person. */
    val aliases: List<String> = emptyList(),
    /** In WhatsApp but not saved in the phone's own contacts, so WhatsApp is how to call them. */
    val fromWhatsAppOnly: Boolean = false,
)

enum class MessageKind { VOICE, PICTURE, VIDEO, TEXT }

/** A received message. Senders not in her contacts have a null [contactId] and stay silent. */
data class InboxItem(
    val id: String,
    val contactId: String?,
    val address: String,
    val kind: MessageKind,
    val fileName: String?,
    val text: String?,
    val time: Long,
    val seen: Boolean = false,
)

data class Settings(
    val countryCode: String = "",
    val speechLanguage: String = "",
    val emergencyNumber: String = "",
    val familyNumber: String = "",
    val helpMessage: String = "",
    val chargePrompt: String = "",
    val favouritesOnly: Boolean = false,
    val voiceApiKey: String = "",
    val voiceModel: String = "",
    val voiceHearsAudio: Boolean = false,
)

object ContactMatch {
    fun byNumber(contacts: List<Contact>, number: String?): Contact? {
        if (number.isNullOrBlank()) return null
        return contacts.firstOrNull { PhoneNumbers.same(it.phone, number) }
    }

    /**
     * WhatsApp notifications are titled with the person's saved name, or their number if unsaved.
     * The notification itself shows they use WhatsApp, so anyone can match. People already marked
     * as using it come first, in case two contacts share a name.
     */
    fun byWhatsAppTitle(contacts: List<Contact>, title: String?): Contact? {
        val wanted = title?.trim().orEmpty()
        if (wanted.isEmpty()) return null
        val markedFirst = contacts.sortedByDescending { it.usesWhatsApp }
        return markedFirst.firstOrNull { it.name.trim().equals(wanted, ignoreCase = true) }
            ?: byNumber(markedFirst, wanted)
    }
}
