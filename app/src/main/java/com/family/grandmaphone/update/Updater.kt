package com.family.grandmaphone.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.family.grandmaphone.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * One-tap updates from the project's GitHub releases. A release is tagged "v<versionCode>" and
 * carries the signed APK. Android asks once to confirm the install, then the app restarts.
 */
object Updater {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Downloading(val version: Int) : State
        data object Installing : State
        data class Failed(val reason: String) : State
    }

    val state = MutableStateFlow<State>(State.Idle)

    private const val TIMEOUT_MILLIS = 20_000

    suspend fun update(context: Context) {
        val app = context.applicationContext
        state.value = State.Checking
        try {
            val release = withContext(Dispatchers.IO) { latestRelease() }
            if (release == null || release.version <= BuildConfig.VERSION_CODE) {
                state.value = State.UpToDate
                return
            }
            state.value = State.Downloading(release.version)
            val apk = File(app.cacheDir, "update.apk")
            withContext(Dispatchers.IO) { download(release.apkUrl, apk) }
            state.value = State.Installing
            withContext(Dispatchers.IO) { install(app, apk) }
        } catch (e: Exception) {
            state.value = State.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private class Release(val version: Int, val apkUrl: String)

    private fun latestRelease(): Release? {
        val connection = open("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (connection.responseCode == 404) return null
            if (connection.responseCode != 200) error("GitHub ${connection.responseCode}")
            val json = JSONObject(connection.inputStream.bufferedReader().readText())
            val version = json.optString("tag_name").removePrefix("v").toIntOrNull() ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name").endsWith(".apk")) {
                    return Release(version, asset.getString("browser_download_url"))
                }
            }
            return null
        } finally {
            connection.disconnect()
        }
    }

    private fun download(url: String, target: File) {
        val connection = open(url)
        try {
            if (connection.responseCode != 200) error("Download ${connection.responseCode}")
            connection.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = TIMEOUT_MILLIS
        readTimeout = TIMEOUT_MILLIS
        instanceFollowRedirects = true
    }

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("update.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val intent = Intent(context, InstallReceiver::class.java)
            val pending = PendingIntent.getBroadcast(
                context, sessionId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
    }
}

/** Shows Android's install confirmation, and reports a failed install. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.state.value = Updater.State.UpToDate
            else -> Updater.state.value = Updater.State.Failed(
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed",
            )
        }
    }
}
