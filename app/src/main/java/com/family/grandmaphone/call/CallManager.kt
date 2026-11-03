package com.family.grandmaphone.call

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.DisconnectCause
import android.telecom.InCallService
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import com.family.grandmaphone.data.Contact
import com.family.grandmaphone.data.ContactMatch
import com.family.grandmaphone.data.Repo
import com.family.grandmaphone.safety.HelpFlow
import com.family.grandmaphone.ui.Notifier
import kotlinx.coroutines.flow.MutableStateFlow

enum class CallPhase { RINGING, DIALING, ACTIVE }

data class CallUi(val phase: CallPhase, val contact: Contact?, val number: String, val speaker: Boolean)

object CallManager {
    val ui = MutableStateFlow<CallUi?>(null)

    private var service: InCallService? = null
    private var current: Call? = null
    private var wasConnected = false
    private var incoming = false

    fun place(context: Context, number: String) {
        val telecom = context.getSystemService(TelecomManager::class.java)
        try {
            telecom.placeCall(Uri.fromParts("tel", number, null), Bundle())
        } catch (_: SecurityException) {
            // The phone permission is missing. The admin screen shows this during setup.
        }
    }

    fun answer() {
        current?.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY)
    }

    fun endOrDecline() {
        val call = current ?: return
        if (call.state == Call.STATE_RINGING) call.reject(false, null) else call.disconnect()
    }

    fun toggleSpeaker() {
        val state = ui.value ?: return
        setSpeaker(!state.speaker)
    }

    private fun setSpeaker(on: Boolean) {
        service?.setAudioRoute(if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE)
    }

    fun onAudioStateChanged(state: CallAudioState?) {
        val on = state?.route == CallAudioState.ROUTE_SPEAKER
        ui.value = ui.value?.copy(speaker = on)
    }

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            if (call === current) update(call)
        }
    }

    fun onAdded(service: InCallService, call: Call) {
        val number = call.details.handle?.schemeSpecificPart.orEmpty()
        if (current != null && call.state == Call.STATE_RINGING) {
            // A second caller would mean a confusing screen, so they get a missed call instead.
            ContactMatch.byNumber(Repo.contacts.value, number)?.let { recordMissed(service, it) }
            call.reject(false, null)
            return
        }
        this.service = service
        current = call
        wasConnected = false
        incoming = call.state == Call.STATE_RINGING
        call.registerCallback(callback)
        update(call)
        if (incoming) {
            val contact = ui.value?.contact
            Ringer.start(service, contact)
            Notifier.incomingCall(service, contact)
        }
        service.startActivity(
            Intent(service, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun onRemoved(call: Call) {
        call.unregisterCallback(callback)
        if (call === current) finish(call)
    }

    /**
     * On a phone with more than one line and no default, Android waits for the phone app to pick
     * one. She can't be asked, so it picks the first, or ends the call if there are none.
     */
    private fun chooseLine(call: Call) {
        val service = service ?: return
        val offered = call.details.intentExtras
            ?.getParcelableArrayList<PhoneAccountHandle>(Call.AVAILABLE_PHONE_ACCOUNTS)
        val line = offered?.firstOrNull() ?: try {
            service.getSystemService(TelecomManager::class.java).callCapablePhoneAccounts.firstOrNull()
        } catch (_: SecurityException) {
            null
        }
        if (line != null) call.phoneAccountSelected(line, false) else call.disconnect()
    }

    private fun update(call: Call) {
        if (call.state == Call.STATE_SELECT_PHONE_ACCOUNT) chooseLine(call)
        val number = call.details.handle?.schemeSpecificPart.orEmpty()
        val previous = ui.value
        val contact = previous?.contact ?: ContactMatch.byNumber(Repo.contacts.value, number)
        val phase = when (call.state) {
            Call.STATE_RINGING -> CallPhase.RINGING
            Call.STATE_ACTIVE, Call.STATE_HOLDING -> CallPhase.ACTIVE
            Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> {
                if (call.state == Call.STATE_DISCONNECTED) finish(call)
                return
            }
            else -> CallPhase.DIALING
        }
        ui.value = CallUi(phase, contact, number, previous?.speaker ?: false)
        if (phase != CallPhase.RINGING) {
            Ringer.stop()
            service?.let { Notifier.cancelIncomingCall(it) }
            if (previous?.phase != phase) setSpeaker(true)
        }
        if (phase == CallPhase.ACTIVE) wasConnected = true
    }

    private fun finish(call: Call) {
        if (current == null) return
        val svc = service
        val contact = ui.value?.contact
        val cause = call.details.disconnectCause?.code
        Ringer.stop()
        svc?.let { Notifier.cancelIncomingCall(it) }
        if (incoming && !wasConnected && cause == DisconnectCause.MISSED && contact != null && svc != null) {
            recordMissed(svc, contact)
        }
        val endedByHer = cause == DisconnectCause.LOCAL || cause == DisconnectCause.REJECTED
        val connected = wasConnected
        current = null
        ui.value = null
        if (svc != null) HelpFlow.onCallEnded(svc, connected, endedByHer)
    }

    private fun recordMissed(context: Context, contact: Contact) {
        Repo.addMissedCall(contact.id)
        Notifier.missedCall(context, contact)
    }
}

/** The phone leaves ringing to this app, so a relative's own voice can be the ringtone. */
object Ringer {
    private var player: MediaPlayer? = null
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    fun start(context: Context, contact: Contact?) {
        stop()
        val clip = contact?.let { Repo.ringClipFile(it.id) }
        if (clip == null || !clip.exists() || !playClip(clip.path)) playDefault(context)
        vibrator = context.getSystemService(Vibrator::class.java)?.also {
            it.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 800), 0), attributes)
        }
    }

    private fun playClip(path: String): Boolean = try {
        player = MediaPlayer().apply {
            setAudioAttributes(attributes)
            setDataSource(path)
            isLooping = true
            prepare()
            start()
        }
        true
    } catch (_: Exception) {
        player?.release()
        player = null
        false
    }

    private fun playDefault(context: Context) {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        ringtone = RingtoneManager.getRingtone(context, uri)?.also {
            it.audioAttributes = attributes
            it.isLooping = true
            it.play()
        }
    }

    fun stop() {
        player?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        player = null
        ringtone?.stop()
        ringtone = null
        vibrator?.cancel()
        vibrator = null
    }
}

class CallService : InCallService() {
    override fun onCallAdded(call: Call) = CallManager.onAdded(this, call)
    override fun onCallRemoved(call: Call) = CallManager.onRemoved(call)
    override fun onCallAudioStateChanged(audioState: CallAudioState?) = CallManager.onAudioStateChanged(audioState)
}

/** Does nothing on purpose. See the manifest. Missed calls are recorded by [CallManager]. */
class MissedCallReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
