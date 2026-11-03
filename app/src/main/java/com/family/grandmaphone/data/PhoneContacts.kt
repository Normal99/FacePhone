package com.family.grandmaphone.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/** One phone number of one person, as the phone's contacts list stores it. */
data class PhoneRow(
    val lookupKey: String,
    val name: String,
    val number: String,
    val isDefaultNumber: Boolean = false,
    val isMobile: Boolean = false,
    val starred: Boolean = false,
)

/** Someone in the phone's contacts who can't be called yet. Only the family's screen lists them. */
data class ContactWithoutNumber(val id: String, val name: String)

/** A person read from the phone's contacts list, before the family's own settings are added. */
data class ImportedContact(
    val id: String,
    val name: String,
    val phone: String,
    val starred: Boolean,
    val usesWhatsApp: Boolean,
    val nickname: String,
    val fromWhatsAppOnly: Boolean = false,
)

object ContactImport {
    /** WhatsApp stores people as "4712345678@s.whatsapp.net". */
    fun numberFromWhatsAppId(id: String): String? {
        val digits = PhoneNumbers.digits(id.substringBefore('@'))
        return if (digits.isEmpty()) null else "+$digits"
    }

    /** Turns a list of numbers into people, choosing the number most likely to reach each one. */
    fun merge(
        rows: List<PhoneRow>,
        whatsAppIds: Set<String> = emptySet(),
        nicknames: Map<String, String> = emptyMap(),
        whatsAppOnlyIds: Set<String> = emptySet(),
    ): List<ImportedContact> =
        rows.filter { it.number.isNotBlank() }
            .groupBy { it.lookupKey }
            .map { (id, numbers) ->
                val best = numbers.firstOrNull { it.isDefaultNumber }
                    ?: numbers.firstOrNull { it.isMobile }
                    ?: numbers.first()
                ImportedContact(
                    id,
                    best.name.ifBlank { best.number },
                    best.number,
                    numbers.any { it.starred },
                    id in whatsAppIds,
                    nicknames[id].orEmpty(),
                    id in whatsAppOnlyIds,
                )
            }
}

/**
 * Keeps the app's people in step with the phone's contacts list. WhatsApp has no list of its own
 * for other apps to read: it marks which phone contacts use it, so those get its buttons.
 */
object PhoneContacts {
    private const val MIME_WHATSAPP_PROFILE = "vnd.android.cursor.item/vnd.com.whatsapp.profile"
    private const val WHATSAPP_ACCOUNT = "com.whatsapp"
    private val worker = Executors.newSingleThreadExecutor()
    private var watching = false

    /** Safe to call often. Newer Android versions crash an app that watches contacts without permission. */
    fun watch(context: Context) {
        val app = context.applicationContext
        val allowed = ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CONTACTS)
        if (!watching && allowed == PackageManager.PERMISSION_GRANTED) {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = syncAsync(app)
            }
            app.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
            watching = true
        }
        syncAsync(app)
    }

    fun syncAsync(context: Context) {
        val app = context.applicationContext
        worker.execute {
            try {
                sync(app)
            } catch (_: Exception) {
                // Permission not granted yet, or the contacts store is busy. The next sync retries.
            }
        }
    }

    private fun sync(context: Context) {
        val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
        if (allowed != PackageManager.PERMISSION_GRANTED) return
        val contactIds = HashMap<String, Long>()
        val rows = readRows(context, contactIds)
        val profiles = readWhatsAppProfiles(context)
        val elsewhere = readContactsOutsideWhatsApp(context)
        val haveNumber = rows.map { it.lookupKey }.toSet()
        // People who are only in WhatsApp may have no phone number row, just their WhatsApp id.
        val extra = profiles.filter { it.lookupKey !in haveNumber }.mapNotNull { profile ->
            val number = ContactImport.numberFromWhatsAppId(profile.whatsAppId) ?: return@mapNotNull null
            contactIds[profile.lookupKey] = profile.contactId
            PhoneRow(profile.lookupKey, profile.name, number)
        }
        val imported = ContactImport.merge(
            rows + extra,
            profiles.map { it.lookupKey }.toSet(),
            readNicknames(context),
            profiles.filter { it.contactId !in elsewhere }.map { it.lookupKey }.toSet(),
        )
        Repo.applyImported(imported)
        Repo.contactsWithoutNumber.value = readContactsWithoutNumber(context)
        var changed = false
        for (person in imported) {
            val contactId = contactIds[person.id] ?: continue
            if (syncPhoto(context, person.id, contactId)) changed = true
        }
        if (changed) Repo.touchFiles()
    }

    private fun readRows(context: Context, contactIds: MutableMap<String, Long>): List<PhoneRow> {
        val rows = ArrayList<PhoneRow>()
        context.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(Phone.CONTACT_ID, Phone.LOOKUP_KEY, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.IS_SUPER_PRIMARY, Phone.TYPE, Phone.STARRED),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val key = cursor.getString(1) ?: continue
                contactIds[key] = cursor.getLong(0)
                rows += PhoneRow(
                    key,
                    cursor.getString(2).orEmpty(),
                    cursor.getString(3).orEmpty(),
                    cursor.getInt(4) != 0,
                    cursor.getInt(5) == Phone.TYPE_MOBILE,
                    cursor.getInt(6) != 0,
                )
            }
        }
        return rows
    }

    private fun readContactsWithoutNumber(context: Context): List<ContactWithoutNumber> {
        val people = ArrayList<ContactWithoutNumber>()
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            "${ContactsContract.Contacts.HAS_PHONE_NUMBER} = 0",
            null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val key = cursor.getString(0) ?: continue
                val name = cursor.getString(1)
                if (!name.isNullOrBlank()) people += ContactWithoutNumber(key, name)
            }
        }
        return people
    }

    private class WhatsAppProfile(val lookupKey: String, val contactId: Long, val name: String, val whatsAppId: String)

    private fun readWhatsAppProfiles(context: Context): List<WhatsAppProfile> {
        val profiles = ArrayList<WhatsAppProfile>()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data.LOOKUP_KEY,
                ContactsContract.Data.CONTACT_ID,
                ContactsContract.Data.DISPLAY_NAME_PRIMARY,
                ContactsContract.Data.DATA1,
            ),
            "${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(MIME_WHATSAPP_PROFILE),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val key = cursor.getString(0) ?: continue
                profiles += WhatsAppProfile(key, cursor.getLong(1), cursor.getString(2).orEmpty(), cursor.getString(3).orEmpty())
            }
        }
        return profiles
    }

    /** Everyone who is saved somewhere other than WhatsApp, such as Samsung's contacts or Google's. */
    private fun readContactsOutsideWhatsApp(context: Context): Set<Long> {
        val ids = HashSet<Long>()
        context.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts.CONTACT_ID),
            "${ContactsContract.RawContacts.ACCOUNT_TYPE} IS NULL OR ${ContactsContract.RawContacts.ACCOUNT_TYPE} != ?",
            arrayOf(WHATSAPP_ACCOUNT),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) ids += cursor.getLong(0)
        }
        return ids
    }

    private fun readNicknames(context: Context): Map<String, String> {
        val names = HashMap<String, String>()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.LOOKUP_KEY, Nickname.NAME),
            "${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(Nickname.CONTENT_ITEM_TYPE),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val key = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                if (name.isNotBlank()) names[key] = name
            }
        }
        return names
    }

    /** Copies the contact's photo into the app. Returns true if it changed. */
    private fun syncPhoto(context: Context, id: String, contactId: Long): Boolean {
        val target = Repo.photoFile(id)
        val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
        val bytes = ContactsContract.Contacts.openContactPhotoInputStream(context.contentResolver, uri, true)
            ?.use { it.readBytes() }
        if (bytes == null) return target.exists() && target.delete()
        if (target.exists() && target.readBytes().contentEquals(bytes)) return false
        target.writeBytes(bytes)
        return true
    }
}
