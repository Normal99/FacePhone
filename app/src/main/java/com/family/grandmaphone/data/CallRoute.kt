package com.family.grandmaphone.data

enum class CallRoute {
    PHONE,
    WHATSAPP;

    companion object {
        /**
         * Numbers in the home country are regular calls. Foreign numbers, and people only WhatsApp
         * knows about, go over WhatsApp. With no home country set, every number is a regular call.
         */
        fun of(number: String, homeCountryCode: String, fromWhatsAppOnly: Boolean = false): CallRoute {
            if (fromWhatsAppOnly) return WHATSAPP
            val home = PhoneNumbers.digits(homeCountryCode)
            if (home.isEmpty()) return PHONE
            val international = PhoneNumbers.international(number, homeCountryCode) ?: return PHONE
            return if (international.startsWith(home)) PHONE else WHATSAPP
        }
    }
}
