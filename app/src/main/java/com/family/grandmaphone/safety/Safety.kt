package com.family.grandmaphone.safety

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationManager
import android.media.AudioAttributes
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.family.grandmaphone.R
import com.family.grandmaphone.call.CallEnd
import com.family.grandmaphone.call.CallHistory
import com.family.grandmaphone.call.CallManager
import com.family.grandmaphone.data.Contact
import com.family.grandmaphone.data.Repo
import com.family.grandmaphone.media.Speaker
import com.family.grandmaphone.sms.SmsClient
import com.family.grandmaphone.ui.AppLocale
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** The help tile: texts family where she is, then calls them in order until someone picks up. */
object HelpFlow {
    private const val NEXT_CALL_DELAY_MILLIS = 2000L
    private var remaining: List<Contact> = emptyList()

    fun helpers(): List<Contact> = Repo.contacts.value.filter { it.helpsInEmergency }

    fun start(context: Context) {
        val app = context.applicationContext
        val people = helpers()
        if (people.isEmpty()) return
        sendTexts(app, people, lastKnownLocation(app))
        requestFreshLocation(app) { fresh -> if (fresh != null) sendTexts(app, people, fresh) }
        remaining = people
        callNext(app)
    }

    private fun callNext(context: Context) {
        val next = remaining.firstOrNull() ?: return
        remaining = remaining.drop(1)
        // Samsung's phone app shows the call, so the end is read from the phone state instead.
        if (!CallHistory.isDefaultPhoneApp(context)) {
            CallEnd.await(context) { answered -> onCallEnded(context, answered, endedByHer = false) }
        }
        CallManager.place(context, next.phone)
    }

    /** Moves on only when nobody answered. If she hung up herself, she's chosen to stop. */
    fun onCallEnded(context: Context, connected: Boolean, endedByHer: Boolean) {
        if (remaining.isEmpty()) return
        if (connected || endedByHer) {
            remaining = emptyList()
            return
        }
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).postDelayed({ callNext(app) }, NEXT_CALL_DELAY_MILLIS)
    }

    private fun sendTexts(context: Context, people: List<Contact>, location: Location?) {
        val message = Repo.settings.value.helpMessage.ifBlank { AppLocale.wrap(context).getString(R.string.default_help_message) }
        val text = if (location != null) {
            "$message https://maps.google.com/?q=${location.latitude},${location.longitude}"
        } else {
            message
        }
        for (person in people) SmsClient.send(context, person.phone, text)
    }

    private fun lastKnownLocation(context: Context): Location? = try {
        val manager = context.getSystemService(LocationManager::class.java)
        manager.getProviders(true)
            .mapNotNull { manager.getLastKnownLocation(it) }
            .maxByOrNull { it.time }
    } catch (_: SecurityException) {
        null
    }

    private fun requestFreshLocation(context: Context, onResult: (Location?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val manager = context.getSystemService(LocationManager::class.java)
            val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .firstOrNull { manager.isProviderEnabled(it) } ?: return
            manager.getCurrentLocation(provider, null, context.mainExecutor) { onResult(it) }
        } catch (_: SecurityException) {
            // The first text has already gone out without a location.
        }
    }
}

/** Android no longer wakes apps for low battery, so this checks every 15 minutes. */
class BatteryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val status = applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return Result.success()
        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 /
            status.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val prefs = applicationContext.getSharedPreferences("battery", Context.MODE_PRIVATE)

        if (plugged || level > RESET_LEVEL) {
            prefs.edit().putBoolean(KEY_ALERTED, false).apply()
            return Result.success()
        }
        if (level !in 0..LOW_LEVEL) return Result.success()

        if (!prefs.getBoolean(KEY_ALERTED, false)) {
            prefs.edit().putBoolean(KEY_ALERTED, true).apply()
            val family = Repo.settings.value.familyNumber
            if (family.isNotBlank()) {
                SmsClient.send(applicationContext, family, AppLocale.wrap(applicationContext).getString(R.string.battery_low_text, level))
            }
        }
        if (!isNight()) speak()
        return Result.success()
    }

    private fun isNight(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour >= 22 || hour < 8
    }

    private fun speak() {
        val clip = Repo.chargeClipFile()
        val main = Handler(Looper.getMainLooper())
        main.post {
            if (clip.exists()) {
                Speaker.play(clip)
            } else {
                Speaker.say(
                    applicationContext,
                    Repo.settings.value.chargePrompt.ifBlank { AppLocale.wrap(applicationContext).getString(R.string.default_charge_prompt) },
                )
            }
        }
    }

    companion object {
        private const val LOW_LEVEL = 15
        private const val RESET_LEVEL = 25
        private const val KEY_ALERTED = "alerted"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "battery",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BatteryWorker>(15, TimeUnit.MINUTES).build(),
            )
        }
    }
}
