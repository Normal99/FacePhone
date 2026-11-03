package com.family.grandmaphone.whatsapp

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import com.family.grandmaphone.call.CallHistory
import com.family.grandmaphone.data.Contact
import com.family.grandmaphone.data.ContactMatch
import com.family.grandmaphone.data.PhoneNumbers
import com.family.grandmaphone.data.Repo
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * WhatsApp has no official way for another app to send or play its messages. This uses what it
 * does allow: opening a chat, starting a call, and handing it a file to send. No unofficial
 * connection libraries, because those can get a number banned.
 */
object WhatsApp {
    private const val PACKAGE = "com.whatsapp"
    private const val MIME_VOICE_CALL = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
    private const val MIME_VIDEO_CALL = "vnd.android.cursor.item/vnd.com.whatsapp.video.call"

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }

    /** WhatsApp's real icon, read from the phone. The project doesn't ship a copy of the logo. */
    fun icon(context: Context): Bitmap? = try {
        context.packageManager.getApplicationIcon(PACKAGE).toBitmap(128, 128)
    } catch (_: Exception) {
        null
    }

    private fun internationalDigits(context: Context, contact: Contact): String? =
        PhoneNumbers.international(contact.phone, Repo.settings.value.countryCode)
            ?: simDigits(context, contact.phone)

    /** With no home country set, local numbers take the SIM's country, so WhatsApp still finds them. */
    private fun simDigits(context: Context, number: String): String? {
        val phone = context.getSystemService(TelephonyManager::class.java) ?: return null
        val iso = phone.simCountryIso.ifBlank { phone.networkCountryIso }.uppercase()
        if (iso.isBlank()) return null
        return PhoneNumberUtils.formatNumberToE164(number, iso)?.let { PhoneNumbers.digits(it) }
    }

    fun openChat(context: Context, contact: Contact): Boolean {
        val digits = internationalDigits(context, contact) ?: return false
        return launch(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits")).setPackage(PACKAGE))
    }

    /** Starts a call using the entry WhatsApp keeps in phone contacts, or opens the chat without one. */
    fun startCall(context: Context, contact: Contact, video: Boolean): Boolean {
        CallHistory.noteWhatsAppCall(context, contact.id)
        val mime = if (video) MIME_VIDEO_CALL else MIME_VOICE_CALL
        val id = callEntry(context, contact, mime)
        if (id != null) {
            val uri = ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, id)
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).setPackage(PACKAGE)
            if (launch(context, intent)) return true
        }
        return openChat(context, contact)
    }

    /**
     * WhatsApp's call entry for this person. Found by contact first, then by the number's last
     * digits, so it works however the number is written and without a home country code.
     */
    private fun callEntry(context: Context, contact: Contact, mime: String): Long? = try {
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID, ContactsContract.Data.LOOKUP_KEY, ContactsContract.Data.DATA1),
            "${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(mime),
            null,
        )?.use { cursor ->
            var byNumber: Long? = null
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == contact.id) return@use cursor.getLong(0)
                val jid = cursor.getString(2).orEmpty().substringBefore('@')
                if (byNumber == null && PhoneNumbers.same(jid, contact.phone)) byNumber = cursor.getLong(0)
            }
            byNumber
        }
    } catch (_: SecurityException) {
        null
    }

    /** Opens WhatsApp with the voice note ready. She taps its send arrow once. */
    fun shareVoice(context: Context, contact: Contact, voice: File): Boolean {
        val digits = internationalDigits(context, contact) ?: return false
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", voice)
        val intent = Intent(Intent.ACTION_SEND)
            .setType("audio/mp4")
            .setPackage(PACKAGE)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra("jid", "$digits@s.whatsapp.net")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return launch(context, intent)
    }

    private fun launch(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        false
    }

    const val LISTENER_PACKAGE = PACKAGE
}

/**
 * Watches WhatsApp's notifications to pulse the right face, and to pick up a profile picture.
 * The picture comes from what WhatsApp puts in the notification, so it only appears once that
 * person has messaged, and only if their privacy settings show it. Nothing leaves the phone.
 */
class WhatsAppListener : NotificationListenerService() {
    override fun onListenerConnected() = sync()
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn?.packageName == WhatsApp.LISTENER_PACKAGE) sync()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn?.packageName == WhatsApp.LISTENER_PACKAGE) sync()
    }

    private fun sync() {
        val notifications = try {
            activeNotifications
        } catch (_: Exception) {
            return
        } ?: return
        val contacts = Repo.contacts.value
        val unread = HashSet<String>()
        for (sbn in notifications) {
            if (sbn.packageName != WhatsApp.LISTENER_PACKAGE) continue
            val notification = sbn.notification
            if (notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) continue
            if (notification.category == android.app.Notification.CATEGORY_CALL) continue
            val extras = notification.extras
            if (extras.getBoolean(android.app.Notification.EXTRA_IS_GROUP_CONVERSATION)) continue
            val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
            val contact = ContactMatch.byWhatsAppTitle(contacts, title) ?: continue
            unread += contact.id
            notification.getLargeIcon()?.let { offerPicture(contact, it) }
        }
        Repo.whatsAppUnread.value = unread
    }

    private fun offerPicture(contact: Contact, icon: Icon) {
        try {
            val bitmap = icon.loadDrawable(this)?.toBitmap() ?: return
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            Repo.saveWhatsAppPhoto(contact.id, out.toByteArray())
        } catch (_: Exception) {
            // A missing picture doesn't matter.
        }
    }
}
