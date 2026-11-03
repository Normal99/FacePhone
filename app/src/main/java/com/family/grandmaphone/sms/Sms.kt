package com.family.grandmaphone.sms

import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.provider.Telephony
import android.telephony.SmsManager
import com.family.grandmaphone.data.ContactMatch
import com.family.grandmaphone.data.InboxItem
import com.family.grandmaphone.data.MessageKind
import com.family.grandmaphone.data.Repo
import com.family.grandmaphone.ui.Notifier
import java.util.UUID

object SmsClient {
    /** Sends a text and records it, which the default SMS app is responsible for doing. */
    fun send(context: Context, toPhone: String, text: String) {
        try {
            val manager = SmsManager.getDefault()
            manager.sendMultipartTextMessage(toPhone, null, manager.divideMessage(text), null, null)
            context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, ContentValues().apply {
                put(Telephony.Sms.ADDRESS, toPhone)
                put(Telephony.Sms.BODY, text)
                put(Telephony.Sms.DATE, System.currentTimeMillis())
                put(Telephony.Sms.READ, 1)
            })
        } catch (_: Exception) {
            // No SIM or no permission. Help and battery alerts have other ways to get noticed.
        }
    }
}

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val first = parts.firstOrNull() ?: return
        val address = first.originatingAddress.orEmpty()
        val body = parts.joinToString("") { it.messageBody.orEmpty() }

        // As the default SMS app, this is the only place the text gets saved.
        context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.DATE_SENT, first.timestampMillis)
            put(Telephony.Sms.READ, 0)
        })

        val contact = ContactMatch.byNumber(Repo.contacts.value, address)
        Repo.addMessage(
            InboxItem(
                UUID.randomUUID().toString(), contact?.id, address, MessageKind.TEXT,
                null, body, System.currentTimeMillis(),
            )
        )
        if (contact != null) Notifier.message(context, contact)
    }
}

/** Sends quick replies chosen on other apps' call screens. Required of the default SMS app. */
class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val number = intent?.data?.schemeSpecificPart
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (!number.isNullOrBlank() && !text.isNullOrBlank()) SmsClient.send(this, number, text)
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
