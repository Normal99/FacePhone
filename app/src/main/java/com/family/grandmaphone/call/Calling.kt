package com.family.grandmaphone.call

import android.content.Context
import androidx.compose.ui.graphics.Color
import com.family.grandmaphone.data.CallRoute
import com.family.grandmaphone.data.Contact
import com.family.grandmaphone.data.Repo
import com.family.grandmaphone.ui.AppColors
import com.family.grandmaphone.whatsapp.WhatsApp

/** Calling a person from her screens, by whichever route suits their number. */
object Calling {
    fun route(contact: Contact): CallRoute =
        CallRoute.of(contact.phone, Repo.settings.value.countryCode, contact.fromWhatsAppOnly)

    /** A foreign number never falls back to a regular call, so without WhatsApp it can't be called. */
    fun canCall(context: Context, contact: Contact): Boolean =
        route(contact) == CallRoute.PHONE || WhatsApp.isInstalled(context)

    fun call(context: Context, contact: Contact): Boolean = when (route(contact)) {
        CallRoute.PHONE -> {
            CallManager.place(context, contact.phone)
            true
        }
        CallRoute.WHATSAPP -> WhatsApp.startCall(context, contact, video = false)
    }

    fun color(contact: Contact): Color =
        if (route(contact) == CallRoute.WHATSAPP) AppColors.WhatsApp else AppColors.Green
}
