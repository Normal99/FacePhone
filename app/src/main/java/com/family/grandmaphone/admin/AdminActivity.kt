package com.family.grandmaphone.admin

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.family.grandmaphone.BuildConfig
import com.family.grandmaphone.R
import com.family.grandmaphone.call.Calling
import com.family.grandmaphone.call.DialActivity
import com.family.grandmaphone.data.CallRoute
import com.family.grandmaphone.data.Contact
import com.family.grandmaphone.data.InboxItem
import com.family.grandmaphone.data.Repo
import com.family.grandmaphone.data.Settings
import com.family.grandmaphone.home.MainActivity
import com.family.grandmaphone.media.Speaker
import com.family.grandmaphone.media.VoiceFormat
import com.family.grandmaphone.media.VoiceRecorder
import com.family.grandmaphone.ui.FacesTheme
import com.family.grandmaphone.ui.OneUiDivider
import com.family.grandmaphone.ui.OneUiGroup
import com.family.grandmaphone.ui.OneUiPage
import com.family.grandmaphone.ui.OneUiRow
import com.family.grandmaphone.ui.rememberImage
import com.family.grandmaphone.update.Updater
import com.family.grandmaphone.voice.VoiceRequest
import com.family.grandmaphone.voice.VoiceResolver
import com.family.grandmaphone.whatsapp.WhatsAppListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** The family's screen, styled like Samsung's Settings app. She gets here only by a hidden gesture. */
class AdminActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FacesTheme {
                var editing by remember { mutableStateOf<Contact?>(null) }
                val contact = editing
                if (contact == null) {
                    AdminHome(onEdit = { editing = it }, onBack = { finish() })
                } else {
                    BackHandler { editing = null }
                    ContactEditor(contact, onClose = { editing = null })
                }
            }
        }
    }
}

private val RUNTIME_PERMISSIONS = arrayOf(
    Manifest.permission.CALL_PHONE,
    Manifest.permission.READ_PHONE_STATE,
    Manifest.permission.READ_CALL_LOG,
    Manifest.permission.SEND_SMS,
    Manifest.permission.RECEIVE_SMS,
    Manifest.permission.READ_SMS,
    Manifest.permission.RECEIVE_MMS,
    Manifest.permission.RECEIVE_WAP_PUSH,
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.WRITE_CONTACTS,
    Manifest.permission.ACCESS_FINE_LOCATION,
)

private fun hasPermissions(context: Context) = RUNTIME_PERMISSIONS.all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

private fun hasRole(context: Context, role: String) =
    context.getSystemService(RoleManager::class.java).isRoleHeld(role)

private fun hasWhatsAppAccess(context: Context): Boolean {
    val component = ComponentName(context, WhatsAppListener::class.java).flattenToString()
    val allowed = AndroidSettings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return allowed?.contains(component) == true
}

private fun isBatteryExempt(context: Context) =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

private val LOUD_STREAMS = intArrayOf(AudioManager.STREAM_RING, AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION)

/** She can't be expected to notice a muted phone, so the setup list checks for one. */
private fun isLoud(context: Context): Boolean {
    val audio = context.getSystemService(AudioManager::class.java)
    return audio.ringerMode == AudioManager.RINGER_MODE_NORMAL && LOUD_STREAMS.all {
        audio.getStreamVolume(it) * 10 >= audio.getStreamMaxVolume(it) * 7
    }
}

private fun makeLoud(context: Context) {
    val audio = context.getSystemService(AudioManager::class.java)
    try {
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        LOUD_STREAMS.forEach { audio.setStreamVolume(it, audio.getStreamMaxVolume(it), 0) }
    } catch (_: SecurityException) {
        // Do Not Disturb blocks this. The item stays on the list, so the family can see it.
    }
}

@Composable
private fun AdminHome(onEdit: (Contact) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val contacts by Repo.contacts.collectAsState()
    val withoutNumber by Repo.contactsWithoutNumber.collectAsState()
    val inbox by Repo.inbox.collectAsState()
    var refresh by remember { mutableStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }

    OneUiPage(stringResource(R.string.admin_title), onBack = onBack) {
        OneUiGroup(stringResource(R.string.contacts)) {
            OneUiRow(
                stringResource(R.string.add_contact),
                subtitle = stringResource(R.string.contacts_explained),
                titleColor = MaterialTheme.colorScheme.primary,
                onClick = { context.startActivity(Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI)) },
            )
            contacts.sortedBy { it.name.lowercase() }.forEach { contact ->
                OneUiDivider()
                val route = when {
                    Calling.route(contact) == CallRoute.PHONE -> null
                    Calling.canCall(context, contact) -> stringResource(R.string.calls_by_whatsapp)
                    else -> stringResource(R.string.needs_whatsapp)
                }
                OneUiRow(
                    contact.name,
                    subtitle = if (contact.hidden) stringResource(R.string.hidden) else listOfNotNull(contact.phone, route).joinToString("\n"),
                    onClick = { onEdit(contact) },
                )
            }
            withoutNumber.sortedBy { it.name.lowercase() }.forEach { person ->
                OneUiDivider()
                OneUiRow(
                    person.name,
                    subtitle = stringResource(R.string.no_number),
                    onClick = {
                        val uri = ContactsContract.Contacts.CONTENT_LOOKUP_URI.buildUpon().appendPath(person.id).build()
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    },
                )
            }
        }

        // Reading refresh makes this section update after returning from a system dialog.
        key(refresh) {
            OneUiGroup(stringResource(R.string.setup)) {
                SetupItem(R.string.role_sms, hasRole(context, RoleManager.ROLE_SMS)) {
                    launcher.launch(context.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_SMS))
                }
                OneUiDivider()
                SetupItem(R.string.grant_permissions, hasPermissions(context)) {
                    permissions.launch(RUNTIME_PERMISSIONS)
                }
                OneUiDivider()
                SetupItem(R.string.whatsapp_access, hasWhatsAppAccess(context)) {
                    launcher.launch(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                OneUiDivider()
                SetupItem(R.string.battery_exempt, isBatteryExempt(context)) {
                    launcher.launch(
                        Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                    )
                }
                OneUiDivider()
                SetupItem(R.string.loud_sound, isLoud(context)) {
                    makeLoud(context)
                    refresh++
                }
                OneUiDivider()
                OneUiRow(
                    stringResource(R.string.keypad),
                    onClick = { context.startActivity(Intent(context, DialActivity::class.java)) },
                )
            }
        }

        key(refresh) { UpdateSection { launcher.launch(it) } }

        SettingsSection()

        OneUiGroup(stringResource(R.string.unknown_messages)) {
            val unknown = inbox.filter { it.contactId == null }.sortedByDescending { it.time }
            if (unknown.isEmpty()) OneUiRow(stringResource(R.string.none))
            unknown.forEachIndexed { index, item ->
                if (index > 0) OneUiDivider()
                UnknownMessage(item)
            }
        }
    }
}

/** Pulls the newest release from GitHub. Android asks once to confirm, then the app restarts. */
@Composable
private fun UpdateSection(open: (Intent) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by Updater.state.collectAsState()
    val busy = state is Updater.State.Checking || state is Updater.State.Downloading || state is Updater.State.Installing
    OneUiGroup(stringResource(R.string.update_title)) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            SetupItem(R.string.allow_updates, false) {
                open(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            }
            OneUiDivider()
        }
        OneUiRow(
            stringResource(R.string.update_now),
            subtitle = stringResource(R.string.version_now, BuildConfig.VERSION_NAME) + "\n" + when (val s = state) {
                Updater.State.Idle -> ""
                Updater.State.Checking -> stringResource(R.string.update_checking)
                Updater.State.UpToDate -> stringResource(R.string.update_none)
                is Updater.State.Downloading -> stringResource(R.string.update_downloading, "1.${s.version}")
                Updater.State.Installing -> stringResource(R.string.update_installing)
                is Updater.State.Failed -> stringResource(R.string.update_failed, s.reason)
            },
            titleColor = MaterialTheme.colorScheme.primary,
            onClick = { if (!busy) scope.launch { Updater.update(context) } },
        )
    }
}

@Composable
private fun SetupItem(label: Int, done: Boolean, onFix: () -> Unit) {
    if (done) {
        OneUiRow(stringResource(label), subtitle = "✓ " + stringResource(R.string.done))
    } else {
        OneUiRow(stringResource(label), titleColor = MaterialTheme.colorScheme.primary, onClick = onFix)
    }
}

@Composable
private fun UnknownMessage(item: InboxItem) {
    val time = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(item.time))
    OneUiRow(item.text ?: item.kind.name, subtitle = "${item.address}  $time")
}

@Composable
private fun SettingsSection() {
    val settings by Repo.settings.collectAsState()
    var countryCode by remember { mutableStateOf(settings.countryCode) }
    var language by remember { mutableStateOf(settings.speechLanguage) }
    var emergency by remember { mutableStateOf(settings.emergencyNumber) }
    var family by remember { mutableStateOf(settings.familyNumber) }
    var help by remember { mutableStateOf(settings.helpMessage) }
    var charge by remember { mutableStateOf(settings.chargePrompt) }
    var favourites by remember { mutableStateOf(settings.favouritesOnly) }
    var apiKey by remember { mutableStateOf(settings.voiceApiKey) }
    var model by remember { mutableStateOf(settings.voiceModel) }
    var hearsAudio by remember { mutableStateOf(settings.voiceHearsAudio) }
    var keyCheck by remember { mutableStateOf<VoiceResolver.KeyCheck?>(null) }
    var checking by remember { mutableStateOf(false) }
    var voiceReady by remember { mutableStateOf<Boolean?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    OneUiGroup(stringResource(R.string.settings)) {
        FieldPadding {
            Field(R.string.country_code, countryCode) { countryCode = it }
            Field(R.string.speech_language, language) {
                language = it
                voiceReady = null
            }
        }
        OneUiRow(
            stringResource(R.string.test_voice),
            subtitle = voiceReady?.let { stringResource(if (it) R.string.voice_ready else R.string.voice_missing) },
            titleColor = MaterialTheme.colorScheme.primary,
            onClick = {
                val tag = language.trim()
                val sample = Configuration(context.resources.configuration)
                if (tag.isNotEmpty()) sample.setLocale(Locale.forLanguageTag(tag))
                val text = context.createConfigurationContext(sample).getString(R.string.default_charge_prompt)
                Speaker.tryVoice(context, tag, text) { voiceReady = it }
            },
        )
        OneUiDivider()
        FieldPadding {
            Field(R.string.emergency_number, emergency) { emergency = it }
            Field(R.string.family_number, family) { family = it }
            Field(R.string.help_message, help, stringResource(R.string.default_help_message)) { help = it }
            Field(R.string.charge_prompt, charge, stringResource(R.string.default_charge_prompt)) { charge = it }
        }
        OneUiDivider()
        ClipRecorder(R.string.record_charge_prompt, Repo.chargeClipFile())
        OneUiDivider()
        LabeledSwitch(R.string.favourites_only, favourites) { favourites = it }
    }

    OneUiGroup(stringResource(R.string.voice_title)) {
        OneUiRow(stringResource(R.string.voice_explained))
        OneUiDivider()
        FieldPadding {
            FieldLabel(R.string.api_key)
            OutlinedTextField(
                apiKey,
                {
                    apiKey = it
                    keyCheck = null
                },
                Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Field(R.string.voice_model, model, VoiceRequest.DEFAULT_MODEL) { model = it }
        }
        OneUiRow(
            stringResource(R.string.test_key),
            subtitle = keyCheck?.let {
                stringResource(
                    when (it) {
                        VoiceResolver.KeyCheck.WORKS -> R.string.key_works
                        VoiceResolver.KeyCheck.LOW_BALANCE -> R.string.key_low_balance
                        VoiceResolver.KeyCheck.REJECTED -> R.string.key_rejected
                        VoiceResolver.KeyCheck.UNREACHABLE -> R.string.key_unreachable
                    }
                )
            },
            titleColor = MaterialTheme.colorScheme.primary,
            onClick = {
                if (apiKey.isNotBlank() && !checking) {
                    checking = true
                    scope.launch {
                        keyCheck = withContext(Dispatchers.IO) { VoiceResolver.checkKey(apiKey.trim()) }
                        checking = false
                    }
                }
            },
        )
        OneUiDivider()
        LabeledSwitch(R.string.voice_hears_audio, hearsAudio, R.string.voice_hears_audio_explained) { hearsAudio = it }
    }

    Button(
        onClick = {
            Repo.saveSettings(
                Settings(
                    countryCode.trim(), language.trim(), emergency.trim(), family.trim(), help.trim(), charge.trim(),
                    favourites, apiKey.trim(), model.trim(), hearsAudio,
                )
            )
        },
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
    ) { Text(stringResource(R.string.save)) }
}

@Composable
private fun FieldPadding(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

/** Sits above its box, because long labels at a large font size don't fit inside one. */
@Composable
private fun FieldLabel(label: Int) {
    Text(stringResource(label), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
}

@Composable
private fun Field(label: Int, value: String, placeholder: String = "", onChange: (String) -> Unit) {
    FieldLabel(label)
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(),
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
    )
}

@Composable
private fun ClipRecorder(label: Int, file: File) {
    var recorder by remember { mutableStateOf<VoiceRecorder?>(null) }
    var hasClip by remember { mutableStateOf(file.exists()) }
    val active = recorder
    if (active == null) {
        OneUiRow(
            stringResource(label),
            titleColor = MaterialTheme.colorScheme.primary,
            onClick = {
                Speaker.stop()
                // Recording to the side keeps the old clip if this one fails.
                val next = VoiceRecorder(File(file.path + ".tmp"), VoiceFormat.AAC)
                if (next.start()) recorder = next
            },
            trailing = if (hasClip) {
                { androidx.compose.material3.TextButton(onClick = { Speaker.play(file) }) { Text(stringResource(R.string.play)) } }
            } else {
                null
            },
        )
    } else {
        OneUiRow(
            stringResource(R.string.stop_recording),
            titleColor = MaterialTheme.colorScheme.error,
            onClick = {
                val temp = File(file.path + ".tmp")
                if (active.stop()) temp.renameTo(file) else temp.delete()
                recorder = null
                hasClip = file.exists()
            },
        )
    }
}

@Composable
private fun ContactEditor(original: Contact, onClose: () -> Unit) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf(!original.hidden) }
    var helper by remember { mutableStateOf(original.helpsInEmergency) }
    var aliases by remember { mutableStateOf(original.aliases.joinToString(", ")) }
    val id = original.id
    val photo = rememberImage(Repo.photoFile(id)) ?: rememberImage(Repo.whatsAppPhotoFile(id))
    val save = {
        Repo.saveContact(
            original.copy(
                hidden = !shown,
                helpsInEmergency = helper,
                aliases = aliases.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            )
        )
    }

    OneUiPage(original.name, onBack = onClose) {
        if (photo != null) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.Center) {
                Image(
                    photo, original.name,
                    Modifier.size(160.dp).clip(RoundedCornerShape(26.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        OneUiGroup {
            OneUiRow(
                original.phone,
                subtitle = stringResource(R.string.open_in_contacts),
                onClick = {
                    val uri = ContactsContract.Contacts.CONTENT_LOOKUP_URI.buildUpon().appendPath(id).build()
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                },
            )
        }
        OneUiGroup {
            LabeledSwitch(R.string.show_on_faces, shown) { shown = it }
            OneUiDivider()
            LabeledSwitch(R.string.call_for_help, helper) { helper = it }
            OneUiDivider()
            FieldPadding { Field(R.string.other_names, aliases) { aliases = it } }
        }
        OneUiGroup {
            ClipRecorder(R.string.record_name, Repo.nameClipFile(id))
            OneUiDivider()
            ClipRecorder(R.string.record_ring, Repo.ringClipFile(id))
            if (photo != null) {
                OneUiDivider()
                OneUiRow(
                    stringResource(R.string.add_to_home),
                    titleColor = MaterialTheme.colorScheme.primary,
                    onClick = {
                        save()
                        pinFace(context, id, original.name)
                    },
                )
            }
        }
        Button(
            onClick = {
                save()
                onClose()
            },
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
        ) { Text(stringResource(R.string.save)) }
    }
}

@Composable
private fun LabeledSwitch(label: Int, checked: Boolean, subtitle: Int? = null, onChange: (Boolean) -> Unit) {
    OneUiRow(
        stringResource(label),
        subtitle = subtitle?.let { stringResource(it) },
        onClick = { onChange(!checked) },
        trailing = { Switch(checked, onChange) },
    )
}

/** Puts a person's face on the Samsung home screen, as its own icon. */
private fun pinFace(context: Context, contactId: String, name: String) {
    val file = Repo.photoFile(contactId).takeIf { it.exists() } ?: Repo.whatsAppPhotoFile(contactId)
    val photo = BitmapFactory.decodeFile(file.path) ?: return
    val side = minOf(photo.width, photo.height)
    val square = Bitmap.createBitmap(photo, (photo.width - side) / 2, (photo.height - side) / 2, side, side)
    val icon = Bitmap.createScaledBitmap(square, 256, 256, true)
    val intent = Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_VIEW)
        .putExtra(MainActivity.EXTRA_CONTACT, contactId)
    val shortcut = ShortcutInfoCompat.Builder(context, contactId)
        .setShortLabel(name)
        .setIcon(IconCompat.createWithBitmap(icon))
        .setIntent(intent)
        .build()
    ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
}
