package com.family.grandmaphone.debug

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager

/**
 * Debug builds only: a pretend phone line, so calls can go through Android's real calling system
 * on a phone with no SIM. See "Testing calls without a SIM" in the README.
 */
class FakeCallProvider : ConnectionService() {
    override fun onCreateIncomingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection {
        val number = request.extras?.getParcelable<Uri>(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS)
        return FakeConnection().apply {
            setAddress(number, TelecomManager.PRESENTATION_ALLOWED)
            setRinging()
            current = this
        }
    }

    override fun onCreateOutgoingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection {
        return FakeConnection().apply {
            setAddress(request.address, TelecomManager.PRESENTATION_ALLOWED)
            setDialing()
            current = this
            // ACTION_ANSWER connects the call, and ACTION_MISS ends it unanswered.
        }
    }

    companion object {
        var current: FakeConnection? = null
    }
}

class FakeConnection : Connection() {
    init {
        connectionCapabilities = CAPABILITY_MUTE
        audioModeIsVoip = true
    }

    override fun onAnswer() = setActive()
    override fun onReject() = end(DisconnectCause.REJECTED)
    override fun onDisconnect() = end(DisconnectCause.LOCAL)
    override fun onAbort() = end(DisconnectCause.CANCELED)

    fun end(code: Int) {
        setDisconnected(DisconnectCause(code))
        destroy()
        if (FakeCallProvider.current === this) FakeCallProvider.current = null
    }
}

/**
 * Driven from a computer, for example:
 * adb shell am broadcast -n com.family.grandmaphone/.debug.FakeCallControl -a ring --es number 0770...
 * Actions: register, unregister, ring, answer, miss, far_end_hangup.
 */
class FakeCallControl : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val telecom = context.getSystemService(TelecomManager::class.java)
        val handle = PhoneAccountHandle(ComponentName(context, FakeCallProvider::class.java), "fake")
        when (intent.action) {
            "register" -> telecom.registerPhoneAccount(
                PhoneAccount.builder(handle, "Fake line")
                    .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                    .addSupportedUriScheme(PhoneAccount.SCHEME_TEL)
                    .build()
            )
            "unregister" -> telecom.unregisterPhoneAccount(handle)
            "ring" -> {
                val number = intent.getStringExtra("number").orEmpty()
                val extras = Bundle().apply {
                    putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, Uri.fromParts("tel", number, null))
                }
                telecom.addNewIncomingCall(handle, extras)
            }
            "answer" -> Handler(Looper.getMainLooper()).post { FakeCallProvider.current?.setActive() }
            "miss" -> FakeCallProvider.current?.end(DisconnectCause.MISSED)
            "far_end_hangup" -> FakeCallProvider.current?.end(DisconnectCause.REMOTE)
        }
    }
}
