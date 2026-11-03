package com.family.grandmaphone.call

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.family.grandmaphone.data.ContactMatch
import com.family.grandmaphone.data.Repo
import com.family.grandmaphone.ui.Notifier
import kotlinx.coroutines.flow.MutableStateFlow

enum class CallKind { INCOMING, OUTGOING, MISSED }

data class CallEntry(val number: String, val kind: CallKind, val time: Long, val seconds: Long)

/**
 * Calls go through Samsung's own phone app, which is the most reliable in-call screen. This app
 * reads the phone's call log to show her history and to notice missed calls. When this app is
 * the default phone app instead, [CallManager] records missed calls itself.
 */
object CallHistory {
    private const val SHOWN = 60
    private const val PREFS = "call_history"
    private const val LAST_MISSED = "last_missed"

    val entries = MutableStateFlow<List<CallEntry>>(emptyList())

    private var watching = false

    fun canRead(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    fun isDefaultPhoneApp(context: Context) =
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_DIALER) == true

    /** Safe to call often. It starts watching once the call log permission is granted. */
    fun watch(context: Context) {
        val app = context.applicationContext
        if (!canRead(app)) return
        if (!watching) {
            watching = true
            app.contentResolver.registerContentObserver(
                CallLog.Calls.CONTENT_URI,
                true,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) = refresh(app)
                },
            )
        }
        refresh(app)
    }

    fun refresh(context: Context) {
        val list = read(context)
        entries.value = list
        noticeMissed(context, list)
    }

    private fun read(context: Context): List<CallEntry> = try {
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
            null,
            null,
            "${CallLog.Calls.DATE} DESC",
        )?.use { cursor ->
            val list = ArrayList<CallEntry>()
            while (cursor.moveToNext() && list.size < SHOWN) {
                val kind = when (cursor.getInt(1)) {
                    CallLog.Calls.INCOMING_TYPE, CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> CallKind.INCOMING
                    CallLog.Calls.OUTGOING_TYPE -> CallKind.OUTGOING
                    CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> CallKind.MISSED
                    else -> continue
                }
                list += CallEntry(cursor.getString(0).orEmpty(), kind, cursor.getLong(2), cursor.getLong(3))
            }
            list
        } ?: emptyList()
    } catch (_: SecurityException) {
        emptyList()
    }

    /** New missed calls from her people get the red dot and a notification with their face. */
    private fun noticeMissed(context: Context, list: List<CallEntry>) {
        if (isDefaultPhoneApp(context)) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val newest = list.maxOfOrNull { it.time } ?: return
        // On the first run, calls already in the log are old news.
        val since = prefs.getLong(LAST_MISSED, -1L).takeIf { it >= 0 } ?: newest
        for (entry in list) {
            if (entry.kind != CallKind.MISSED || entry.time <= since) continue
            val contact = ContactMatch.byNumber(Repo.contacts.value, entry.number) ?: continue
            Repo.addMissedCall(contact.id)
            Notifier.missedCall(context, contact)
        }
        prefs.edit().putLong(LAST_MISSED, maxOf(since, newest)).apply()
    }

    /** The newest outgoing call, used to tell whether a help call was answered. */
    fun lastOutgoing(context: Context): CallEntry? = read(context).firstOrNull { it.kind == CallKind.OUTGOING }
}

/**
 * Tells when a call placed through Samsung's phone app ends, and whether it was answered,
 * so the help button can try the next person.
 */
object CallEnd {
    private const val LOG_WRITE_DELAY_MILLIS = 1500L

    private var listener: PhoneStateListener? = null

    @Suppress("DEPRECATION")
    fun await(context: Context, onEnded: (answered: Boolean) -> Unit) {
        val app = context.applicationContext
        val telephony = app.getSystemService(TelephonyManager::class.java) ?: return
        stop(app)
        val startedAt = System.currentTimeMillis()
        var offHook = false
        val handler = Handler(Looper.getMainLooper())
        val next = object : PhoneStateListener(app.mainExecutor) {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                when (state) {
                    TelephonyManager.CALL_STATE_OFFHOOK -> offHook = true
                    TelephonyManager.CALL_STATE_IDLE -> if (offHook) {
                        stop(app)
                        // The phone app writes the call log just after the call ends.
                        handler.postDelayed({
                            val last = CallHistory.lastOutgoing(app)
                            val answered = last != null && last.time >= startedAt - 5000 && last.seconds > 0
                            onEnded(answered)
                        }, LOG_WRITE_DELAY_MILLIS)
                    }
                }
            }
        }
        listener = next
        telephony.listen(next, PhoneStateListener.LISTEN_CALL_STATE)
    }

    @Suppress("DEPRECATION")
    fun stop(context: Context) {
        val current = listener ?: return
        context.getSystemService(TelephonyManager::class.java)?.listen(current, PhoneStateListener.LISTEN_NONE)
        listener = null
    }
}
