package com.family.grandmaphone.mms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.FileProvider
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.family.grandmaphone.data.ContactMatch
import com.family.grandmaphone.data.InboxItem
import com.family.grandmaphone.data.MessageKind
import com.family.grandmaphone.data.Repo
import com.family.grandmaphone.ui.Notifier
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class SendStatus { IDLE, SENDING, SENT, FAILED }

object MmsClient {
    val sendStatus = MutableStateFlow(SendStatus.IDLE)

    private const val DEFAULT_MAX_BYTES = 300 * 1024
    private const val ACTION_SENT = "com.family.grandmaphone.MMS_SENT"
    private const val ACTION_ACK_SENT = "com.family.grandmaphone.MMS_ACK_SENT"
    const val ACTION_DOWNLOADED = "com.family.grandmaphone.MMS_DOWNLOADED"
    const val EXTRA_FILE = "file"
    const val EXTRA_TRANSACTION = "transaction"
    const val EXTRA_LOCATION = "location"
    const val EXTRA_ATTEMPT = "attempt"

    private fun workDir(context: Context) = File(context.cacheDir, "mms").apply { mkdirs() }
    private fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)

    fun maxMessageBytes(): Int = try {
        SmsManager.getDefault().carrierConfigValues
            .getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, DEFAULT_MAX_BYTES)
            .takeIf { it > 0 } ?: DEFAULT_MAX_BYTES
    } catch (_: Exception) {
        DEFAULT_MAX_BYTES
    }

    fun maxVoiceSeconds(): Int = MmsRouting.maxVoiceSeconds(maxMessageBytes())

    fun sendVoice(context: Context, toPhone: String, voice: File) {
        send(context, toPhone, MmsPart("audio/amr", voice.readBytes()), "voice.amr")
    }

    fun sendPicture(context: Context, toPhone: String, picture: File) {
        val jpeg = compressToFit(picture, maxMessageBytes() - 4096)
        if (jpeg == null) {
            sendStatus.value = SendStatus.FAILED
            return
        }
        send(context, toPhone, MmsPart("image/jpeg", jpeg), "picture.jpg")
    }

    private fun send(context: Context, toPhone: String, part: MmsPart, fileName: String) {
        sendStatus.value = SendStatus.SENDING
        try {
            val transactionId = UUID.randomUUID().toString().replace("-", "")
            val file = File(workDir(context), "$transactionId.send")
            file.writeBytes(MmsPdu.encodeSendRequest(transactionId, toPhone, part, fileName))
            SmsManager.getDefault().sendMultimediaMessage(
                context, uriFor(context, file), null, null, result(context, ACTION_SENT, file),
            )
        } catch (_: Exception) {
            sendStatus.value = SendStatus.FAILED
        }
    }

    fun download(context: Context, notification: MmsNotification, attempt: Int = 0) {
        val file = File(workDir(context), "${UUID.randomUUID()}.received")
        file.createNewFile()
        try {
            val callback = result(context, ACTION_DOWNLOADED, file) {
                putExtra(EXTRA_TRANSACTION, notification.transactionId)
                putExtra(EXTRA_LOCATION, notification.contentLocation)
                putExtra(EXTRA_ATTEMPT, attempt)
            }
            SmsManager.getDefault().downloadMultimediaMessage(
                context, notification.contentLocation, uriFor(context, file), null, callback,
            )
        } catch (_: Exception) {
            file.delete()
            retryLater(context, notification.transactionId, notification.contentLocation, attempt)
        }
    }

    fun acknowledge(context: Context, transactionId: String) {
        try {
            val file = File(workDir(context), "${UUID.randomUUID()}.ack")
            file.writeBytes(MmsPdu.encodeNotifyResponse(transactionId))
            SmsManager.getDefault().sendMultimediaMessage(
                context, uriFor(context, file), null, null, result(context, ACTION_ACK_SENT, file),
            )
        } catch (_: Exception) {
            // The carrier may announce the message again, and duplicates are ignored.
        }
    }

    fun retryLater(context: Context, transactionId: String, location: String, attempt: Int) {
        if (attempt >= MAX_ATTEMPTS) return
        val request = OneTimeWorkRequestBuilder<MmsRetryWorker>()
            .setInitialDelay(5L * (attempt + 1), TimeUnit.MINUTES)
            .setInputData(
                Data.Builder()
                    .putString(EXTRA_TRANSACTION, transactionId)
                    .putString(EXTRA_LOCATION, location)
                    .putInt(EXTRA_ATTEMPT, attempt + 1)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("mms-$transactionId", ExistingWorkPolicy.REPLACE, request)
    }

    private const val MAX_ATTEMPTS = 3
    private var nextRequestCode = 1

    private fun result(
        context: Context,
        action: String,
        file: File,
        extras: Intent.() -> Unit = {},
    ): PendingIntent {
        val intent = Intent(context, MmsResultReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_FILE, file.path)
            .apply(extras)
        // Mutable because the phone system adds details to the result.
        return PendingIntent.getBroadcast(
            context, nextRequestCode++, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE,
        )
    }

    fun onResult(context: Context, intent: Intent, resultCode: Int) {
        val file = intent.getStringExtra(EXTRA_FILE)?.let(::File)
        when (intent.action) {
            ACTION_SENT -> {
                sendStatus.value = if (resultCode == Activity.RESULT_OK) SendStatus.SENT else SendStatus.FAILED
                file?.delete()
            }
            ACTION_ACK_SENT -> file?.delete()
            ACTION_DOWNLOADED -> {
                val transaction = intent.getStringExtra(EXTRA_TRANSACTION) ?: return
                val location = intent.getStringExtra(EXTRA_LOCATION) ?: return
                val attempt = intent.getIntExtra(EXTRA_ATTEMPT, 0)
                val stored = resultCode == Activity.RESULT_OK && file != null && storeDownloaded(context, file)
                file?.delete()
                if (stored) acknowledge(context, transaction) else retryLater(context, transaction, location, attempt)
            }
        }
    }

    private fun storeDownloaded(context: Context, file: File): Boolean {
        val message = try {
            MmsPdu.parseRetrieved(file.readBytes())
        } catch (_: MmsFormatException) {
            return false
        }
        val address = message.from.orEmpty()
        val contact = ContactMatch.byNumber(Repo.contacts.value, address)
        var added = false
        for (part in message.parts) {
            val kind = MmsRouting.kindOf(part.contentType) ?: continue
            val id = UUID.randomUUID().toString()
            var fileName: String? = null
            var text: String? = null
            if (kind == MessageKind.TEXT) {
                text = part.text
            } else {
                fileName = "$id.${MmsRouting.extensionOf(part.contentType)}"
                Repo.inboxFile(fileName).writeBytes(part.data)
            }
            Repo.addMessage(InboxItem(id, contact?.id, address, kind, fileName, text, System.currentTimeMillis()))
            added = true
        }
        if (added && contact != null) Notifier.message(context, contact)
        return true
    }

    /** Shrinks a photo until it fits in an MMS. Null if it can't be read. */
    private fun compressToFit(picture: File, maxBytes: Int): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(picture.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1280) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        var bitmap = BitmapFactory.decodeFile(picture.path, options) ?: return null
        var quality = 85
        while (true) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= maxBytes) return out.toByteArray()
            if (quality > 40) {
                quality -= 15
            } else {
                if (bitmap.width < 200) return null
                bitmap = Bitmap.createScaledBitmap(bitmap, bitmap.width / 2, bitmap.height / 2, true)
            }
        }
    }
}

class MmsPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val pdu = intent.getByteArrayExtra("data") ?: return
        if (MmsPdu.messageType(pdu) != MmsPdu.TYPE_NOTIFICATION_IND) return
        val notification = try {
            MmsPdu.parseNotification(pdu)
        } catch (_: MmsFormatException) {
            return
        }
        if (Repo.firstSightOfMms(notification.transactionId)) MmsClient.download(context, notification)
    }
}

class MmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        MmsClient.onResult(context, intent, resultCode)
    }
}

class MmsRetryWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val transaction = inputData.getString(MmsClient.EXTRA_TRANSACTION) ?: return Result.failure()
        val location = inputData.getString(MmsClient.EXTRA_LOCATION) ?: return Result.failure()
        val attempt = inputData.getInt(MmsClient.EXTRA_ATTEMPT, 1)
        MmsClient.download(applicationContext, MmsNotification(transaction, location, null), attempt)
        return Result.success()
    }
}
