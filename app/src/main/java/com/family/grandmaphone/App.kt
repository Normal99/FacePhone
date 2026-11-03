package com.family.grandmaphone

import android.app.Application
import com.family.grandmaphone.call.CallHistory
import com.family.grandmaphone.data.PhoneContacts
import com.family.grandmaphone.data.Repo
import com.family.grandmaphone.safety.BatteryWorker
import com.family.grandmaphone.ui.Notifier
import com.family.grandmaphone.widget.FacesWidget
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        Notifier.createChannels(this)
        BatteryWorker.schedule(this)
        PhoneContacts.watch(this)
        CallHistory.watch(this)
        keepWidgetCurrent()
    }

    /** Redraws the faces widget shortly after people, photos, settings or calls change. */
    private fun keepWidgetCurrent() {
        val changes = listOf(
            Repo.contacts, Repo.settings, Repo.filesVersion, Repo.missedCalls,
            CallHistory.entries, CallHistory.whatsAppCalls,
        )
        MainScope().launch {
            combine(changes) { it.toList() }.collectLatest {
                delay(WIDGET_DELAY_MILLIS)
                FacesWidget.refresh(this@App)
            }
        }
    }

    private companion object {
        const val WIDGET_DELAY_MILLIS = 500L
    }
}
