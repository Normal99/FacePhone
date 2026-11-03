package com.family.grandmaphone.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import com.family.grandmaphone.R
import com.family.grandmaphone.call.CallActivity
import com.family.grandmaphone.data.Contact
import com.family.grandmaphone.data.Repo
import com.family.grandmaphone.home.MainActivity

/** Notifications lead with the person's face, with their name and a few words as a fallback. */
object Notifier {
    private const val CHANNEL_CALLS = "calls"
    private const val CHANNEL_MESSAGES = "messages"
    private const val ID_INCOMING_CALL = 1

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CALLS, "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                // The app plays its own ringtone.
                setSound(null, null)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun build(context: Context, channel: String, contact: Contact?, text: Int): Notification.Builder {
        val strings = AppLocale.wrap(context)
        val builder = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(contact?.name ?: strings.getString(R.string.unknown_caller))
            .setContentText(strings.getString(text))
        contact?.let { Repo.photoFile(it.id) }?.takeIf { it.exists() }?.let {
            BitmapFactory.decodeFile(it.path)?.let(builder::setLargeIcon)
        }
        return builder
    }

    private fun openContact(context: Context, contact: Contact): PendingIntent =
        PendingIntent.getActivity(
            context, contact.id.hashCode(),
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_CONTACT, contact.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun incomingCall(context: Context, contact: Contact?) {
        val screen = PendingIntent.getActivity(
            context, 0, Intent(context, CallActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = build(context, CHANNEL_CALLS, contact, R.string.incoming_call)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setFullScreenIntent(screen, true)
            .setContentIntent(screen)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_INCOMING_CALL, notification)
    }

    fun cancelIncomingCall(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID_INCOMING_CALL)
    }

    fun missedCall(context: Context, contact: Contact) = post(context, contact, "missed", R.string.missed_call)

    fun message(context: Context, contact: Contact) = post(context, contact, "message", R.string.new_message)

    private fun post(context: Context, contact: Contact, tag: String, text: Int) {
        val notification = build(context, CHANNEL_MESSAGES, contact, text)
            .setContentIntent(openContact(context, contact))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(tag, contact.id.hashCode(), notification)
    }

    fun clear(context: Context, contactId: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel("missed", contactId.hashCode())
        manager.cancel("message", contactId.hashCode())
    }
}
