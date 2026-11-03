package com.family.grandmaphone.data

import android.content.ContentProviderOperation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.ContactsContract
import java.io.ByteArrayOutputStream

/**
 * Sets a person's photo from the camera or the gallery. It goes into the phone's own contacts,
 * so Samsung's phone app shows it on calls too, and into this app straight away.
 */
object ContactPhoto {
    private const val SIDE = 720
    private const val WHATSAPP_ACCOUNT = "com.whatsapp"

    fun set(context: Context, contact: Contact, source: Uri): Boolean {
        val jpeg = try {
            squareJpeg(context, source)
        } catch (_: Exception) {
            null
        } ?: return false
        Repo.photoFile(contact.id).writeBytes(jpeg)
        Repo.touchFiles()
        return try {
            writeToContacts(context, contact.id, jpeg)
        } catch (_: Exception) {
            false
        }
    }

    /** A centred square, turned the right way up, small enough for the contacts app. */
    private fun squareJpeg(context: Context, source: Uri): ByteArray? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIDE) sample *= 2
        val decoded = resolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotation = resolver.openInputStream(source)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        val side = minOf(decoded.width, decoded.height)
        val scale = minOf(1f, SIDE.toFloat() / side)
        val matrix = Matrix().apply {
            postRotate(rotation)
            postScale(scale, scale)
        }
        val square = Bitmap.createBitmap(
            decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side, matrix, true,
        )
        val out = ByteArrayOutputStream()
        square.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray()
    }

    /** Replaces the photo on the person's own contact entry, not WhatsApp's. */
    private fun writeToContacts(context: Context, lookupKey: String, jpeg: ByteArray): Boolean {
        val resolver = context.contentResolver
        val lookup = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, lookupKey)
        val contactUri = ContactsContract.Contacts.lookupContact(resolver, lookup) ?: return false
        val contactId = contactUri.lastPathSegment ?: return false
        val rawId = resolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.ACCOUNT_TYPE),
            "${ContactsContract.RawContacts.CONTACT_ID} = ? AND ${ContactsContract.RawContacts.DELETED} = 0",
            arrayOf(contactId),
            null,
        )?.use { cursor ->
            var chosen: Long? = null
            while (cursor.moveToNext()) {
                if (cursor.getString(1) != WHATSAPP_ACCOUNT) {
                    chosen = cursor.getLong(0)
                    break
                }
            }
            chosen
        } ?: return false
        val existing = resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID),
            "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(rawId.toString(), ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE),
            null,
        )?.use { if (it.moveToFirst()) it.getLong(0) else null }
        val operation = if (existing != null) {
            ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection("${ContactsContract.Data._ID} = ?", arrayOf(existing.toString()))
        } else {
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
        }
        resolver.applyBatch(
            ContactsContract.AUTHORITY,
            arrayListOf(operation.withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, jpeg).build()),
        )
        return true
    }
}
