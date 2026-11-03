package com.family.grandmaphone

import android.app.Application
import com.family.grandmaphone.call.CallHistory
import com.family.grandmaphone.data.PhoneContacts
import com.family.grandmaphone.data.Repo
import com.family.grandmaphone.safety.BatteryWorker
import com.family.grandmaphone.ui.Notifier

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        Notifier.createChannels(this)
        BatteryWorker.schedule(this)
        PhoneContacts.watch(this)
        CallHistory.watch(this)
    }
}
