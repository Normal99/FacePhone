package com.family.grandmaphone.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import com.family.grandmaphone.ui.rememberPhotoPicker
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallMissed
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.family.grandmaphone.R
import com.family.grandmaphone.admin.AdminActivity
import com.family.grandmaphone.call.CallEntry
import com.family.grandmaphone.call.CallHistory
import com.family.grandmaphone.call.CallKind
import com.family.grandmaphone.call.CallManager
import com.family.grandmaphone.call.Calling
import com.family.grandmaphone.data.CallRoute
import com.family.grandmaphone.data.Contact
import com.family.grandmaphone.data.ContactMatch
import com.family.grandmaphone.data.InboxItem
import com.family.grandmaphone.data.MessageKind
import com.family.grandmaphone.data.PhoneContacts
import com.family.grandmaphone.data.Repo
import com.family.grandmaphone.media.Speaker
import com.family.grandmaphone.media.VoiceFormat
import com.family.grandmaphone.media.VoiceRecorder
import com.family.grandmaphone.media.beep
import com.family.grandmaphone.mms.MmsClient
import com.family.grandmaphone.mms.SendStatus
import com.family.grandmaphone.safety.HelpFlow
import com.family.grandmaphone.ui.AppColors
import com.family.grandmaphone.ui.AppLocale
import com.family.grandmaphone.ui.FacesTheme
import com.family.grandmaphone.ui.FaceImage
import com.family.grandmaphone.ui.HoldButton
import com.family.grandmaphone.ui.Notifier
import com.family.grandmaphone.ui.RoundIconButton
import com.family.grandmaphone.ui.anyTap
import com.family.grandmaphone.ui.forgivingTap
import com.family.grandmaphone.ui.rememberImage
import com.family.grandmaphone.voice.VoiceScreen
import com.family.grandmaphone.whatsapp.WhatsApp
import kotlinx.coroutines.delay
import java.io.File

private sealed interface Screen {
    data object Faces : Screen
    data class Person(val contactId: String) : Screen
    data class Record(val contactId: String, val viaWhatsApp: Boolean) : Screen
    data class Sending(val contactId: String, val file: File, val isPicture: Boolean) : Screen
    data class Viewer(val contactId: String, val itemId: String) : Screen
    data object Voice : Screen
    data object History : Screen
}

/** Her screens. Faces, icons and sound come first, with names and short labels as a fallback. */
class MainActivity : ComponentActivity() {
    private val screen = mutableStateOf<Screen>(Screen.Faces)
    private val languageShown = Repo.settings.value.speechLanguage

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent {
            FacesTheme {
                val current by screen
                val go: (Screen) -> Unit = { screen.value = it }
                BackHandler(current != Screen.Faces) { go(backFrom(current)) }
                Box(Modifier.fillMaxSize()) {
                    when (val s = current) {
                        Screen.Faces -> FacesScreen(go)
                        is Screen.Person -> Repo.contact(s.contactId)?.let { PersonScreen(it, go) }
                        is Screen.Record -> Repo.contact(s.contactId)?.let { RecordScreen(it, s.viaWhatsApp, go) }
                        is Screen.Sending -> Repo.contact(s.contactId)?.let { SendingScreen(it, s, go) }
                        is Screen.Viewer -> ViewerScreen(s, go)
                        Screen.History -> HistoryScreen(go)
                        Screen.Voice -> VoiceScreen(
                            onClose = { go(Screen.Faces) },
                            onMessage = { go(Screen.Record(it.id, viaWhatsApp = false)) },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        PhoneContacts.watch(this)
        CallHistory.watch(this)
        if (Repo.settings.value.speechLanguage != languageShown) recreate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_CONTACT) ?: return
        if (Repo.contact(id) != null) screen.value = Screen.Person(id)
    }

    private fun backFrom(screen: Screen): Screen = when (screen) {
        is Screen.Person, Screen.Faces, Screen.Voice, Screen.History -> Screen.Faces
        is Screen.Record -> Screen.Person(screen.contactId)
        is Screen.Sending -> Screen.Person(screen.contactId)
        is Screen.Viewer -> Screen.Person(screen.contactId)
    }

    companion object {
        const val EXTRA_CONTACT = "contact"
    }
}

@Composable
private fun pulse(): Float {
    val transition = rememberInfiniteTransition(label = "pulse")
    val value by transition.animateFloat(
        0.25f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse",
    )
    return value
}

// ---- All the faces ----

private const val ADMIN_TAPS = 7
private const val ADMIN_TAP_WINDOW_MILLIS = 6000L

@Composable
private fun FacesScreen(go: (Screen) -> Unit) {
    val context = LocalContext.current
    val everyone by Repo.contacts.collectAsState()
    val inbox by Repo.inbox.collectAsState()
    val missed by Repo.missedCalls.collectAsState()
    val whatsApp by Repo.whatsAppUnread.collectAsState()
    val settings by Repo.settings.collectAsState()
    val calls by CallHistory.entries.collectAsState()
    val whatsAppCalls by CallHistory.whatsAppCalls.collectAsState()
    val contacts = remember(everyone, settings, calls, whatsAppCalls) {
        CallHistory.recentFirst(Repo.visibleContacts(everyone, settings), calls, whatsAppCalls)
    }
    val openAdmin = { context.startActivity(Intent(context, AdminActivity::class.java)) }

    Column(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            GridCells.Fixed(2),
            Modifier.weight(1f),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(2) }) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.people), fontSize = 36.sp, fontWeight = FontWeight.Light)
                    // The family's settings.
                    Box(
                        Modifier.align(Alignment.CenterStart).size(64.dp).clip(CircleShape)
                            .background(AppColors.Grey).forgivingTap { openAdmin() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Settings, stringResource(R.string.settings), tint = Color.White, modifier = Modifier.size(36.dp))
                    }
                }
            }
            items(contacts, key = { it.id }) { contact ->
                val unseen = contact.id in whatsApp || inbox.any { it.contactId == contact.id && !it.seen }
                FaceTile(contact, unseen, contact.id in missed) { go(Screen.Person(contact.id)) }
            }
            if (contacts.isEmpty()) {
                item {
                    Box(
                        Modifier.aspectRatio(1f).clip(RoundedCornerShape(26.dp)).background(AppColors.Grey)
                            .forgivingTap { openAdmin() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Add, "Add", tint = Color.White, modifier = Modifier.size(96.dp))
                    }
                }
            }
        }
        val hasHelpers = everyone.any { it.helpsInEmergency }
        if (everyone.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    Modifier.size(96.dp).clip(CircleShape).background(AppColors.Blue).forgivingTap { go(Screen.History) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.History, stringResource(R.string.call_history), tint = Color.White, modifier = Modifier.size(56.dp))
                }
                Box(
                    Modifier.weight(1f).height(96.dp).clip(RoundedCornerShape(48.dp))
                        .background(AppColors.Green).forgivingTap { go(Screen.Voice) },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Filled.Mic, null, tint = Color.White, modifier = Modifier.size(56.dp))
                        Text(stringResource(R.string.say_who), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        if (hasHelpers || settings.emergencyNumber.isNotBlank()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (hasHelpers) {
                    HoldButton(Icons.Filled.SupportAgent, AppColors.Blue, stringResource(R.string.help), Modifier.weight(1f).height(96.dp), label = stringResource(R.string.help)) {
                        HelpFlow.start(context)
                    }
                }
                if (settings.emergencyNumber.isNotBlank()) {
                    HoldButton(Icons.Filled.LocalHospital, AppColors.Red, stringResource(R.string.emergency), Modifier.weight(1f).height(96.dp), label = stringResource(R.string.emergency)) {
                        CallManager.place(context, settings.emergencyNumber)
                    }
                }
            }
        }
    }
    AdminCorner(openAdmin)
}

/** Recent calls as faces. Arrows show the direction, red means missed. Tapping opens that person. */
@Composable
private fun HistoryScreen(go: (Screen) -> Unit) {
    val context = LocalContext.current
    val calls by CallHistory.entries.collectAsState()
    val contacts by Repo.contacts.collectAsState()
    LaunchedEffect(Unit) { CallHistory.watch(context) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(Icons.Filled.ArrowBack, AppColors.Dark, 72, stringResource(R.string.back)) { go(Screen.Faces) }
            Text(
                stringResource(R.string.call_history),
                Modifier.weight(1f),
                fontSize = 30.sp,
                fontWeight = FontWeight.Light,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(72.dp))
        }
        if (calls.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.History, null, tint = AppColors.Grey, modifier = Modifier.size(120.dp))
            }
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(calls, key = { it.time.toString() + it.number }) { call ->
                val contact = ContactMatch.byNumber(contacts, call.number)
                HistoryRow(call, contact) { if (contact != null) go(Screen.Person(contact.id)) }
            }
        }
    }
}

@Composable
private fun HistoryRow(call: CallEntry, contact: Contact?, onOpen: () -> Unit) {
    val (icon, color) = when (call.kind) {
        CallKind.INCOMING -> Icons.Filled.CallReceived to AppColors.Green
        CallKind.OUTGOING -> Icons.Filled.CallMade to AppColors.Blue
        CallKind.MISSED -> Icons.Filled.CallMissed to AppColors.Red
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp))
            .background(MaterialTheme.colorScheme.surface)
            .forgivingTap(onOpen)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        FaceImage(contact, Modifier.size(88.dp).clip(RoundedCornerShape(22.dp)))
        Column(Modifier.weight(1f)) {
            Text(
                contact?.name ?: call.number,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(whenText(call.time), fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.size(64.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(40.dp))
        }
    }
}

/** "14:05" for today, otherwise the day and time. */
private fun whenText(time: Long): String {
    val today = android.text.format.DateUtils.isToday(time)
    val format = if (today) java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
    else java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
    return format.format(java.util.Date(time))
}

/** Seven quick taps in the top-right corner open the family's settings. */
@Composable
private fun AdminCorner(open: () -> Unit) {
    var taps by remember { mutableStateOf(emptyList<Long>()) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        Box(
            Modifier.size(64.dp).anyTap {
                val now = System.currentTimeMillis()
                taps = (taps + now).filter { now - it < ADMIN_TAP_WINDOW_MILLIS }
                if (taps.size >= ADMIN_TAPS) {
                    taps = emptyList()
                    open()
                }
            }
        )
    }
}

/** The name sits below the photo, so large text settings can never cover the face. */
@Composable
private fun FaceTile(contact: Contact, unseen: Boolean, missed: Boolean, onTap: () -> Unit) {
    val glow = if (unseen) pulse() else 0f
    Column(Modifier.forgivingTap(onTap), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.aspectRatio(1f)) {
            FaceImage(
                contact,
                Modifier.fillMaxSize().clip(RoundedCornerShape(26.dp))
                    .border(8.dp, AppColors.Green.copy(alpha = glow), RoundedCornerShape(26.dp)),
            )
            if (missed) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(36.dp).clip(CircleShape).background(AppColors.Red)
                )
            }
        }
        Text(
            contact.name,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

@Composable
private fun NameStrip(name: String, modifier: Modifier = Modifier) {
    Text(
        name,
        modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = 26.dp, bottomEnd = 26.dp))
            .background(Color.Black.copy(alpha = 0.6f)).padding(6.dp),
        color = Color.White,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        maxLines = 2,
    )
}

// ---- One person ----

@Composable
private fun PersonScreen(contact: Contact, go: (Screen) -> Unit) {
    val context = LocalContext.current
    val inbox by Repo.inbox.collectAsState()
    val whatsAppUnread by Repo.whatsAppUnread.collectAsState()
    val messages = inbox.filter { it.contactId == contact.id }.sortedWith(
        compareBy<InboxItem> { it.seen }.thenByDescending { it.time }
    ).take(MESSAGES_SHOWN)
    val photoTarget = remember(contact.id) { File(context.cacheDir, "camera.jpg") }
    val filesVersion by Repo.filesVersion.collectAsState()
    val hasPhoto = remember(contact.id, filesVersion) {
        Repo.photoFile(contact.id).exists() || Repo.whatsAppPhotoFile(contact.id).exists()
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        if (taken) go(Screen.Sending(contact.id, photoTarget, isPicture = true))
    }

    DisposableEffect(contact.id) {
        // Opening a person says their name in their own voice, and counts as seeing a missed call.
        Speaker.play(Repo.nameClipFile(contact.id))
        Repo.clearMissedCall(contact.id)
        Notifier.clear(context, contact.id)
        onDispose { Speaker.stop() }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            FaceImage(contact, Modifier.fillMaxSize().clip(RoundedCornerShape(26.dp))
                .forgivingTap { Speaker.play(Repo.nameClipFile(contact.id)) })
            RoundIconButton(Icons.Filled.ArrowBack, AppColors.Dark, 72, stringResource(R.string.back), Modifier.padding(8.dp)) {
                go(Screen.Faces)
            }
            // The family's settings for this person.
            RoundIconButton(Icons.Filled.Settings, AppColors.Dark, 72, stringResource(R.string.settings), Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                context.startActivity(Intent(context, AdminActivity::class.java).putExtra(AdminActivity.EXTRA_EDIT, contact.id))
            }
            if (!hasPhoto) AddPhoto(contact, Modifier.align(Alignment.Center))
            NameStrip(contact.name, Modifier.align(Alignment.BottomCenter))
        }
        if (messages.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(messages, key = { it.id }) { item ->
                    MessageTile(item) { go(Screen.Viewer(contact.id, item.id)) }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom,
        ) {
            RoundIconButton(Icons.Filled.Mic, AppColors.Blue, 96, stringResource(R.string.message), label = stringResource(R.string.message)) {
                go(Screen.Record(contact.id, viaWhatsApp = false))
            }
            if (Calling.canCall(context, contact)) {
                RoundIconButton(Icons.Filled.Call, Calling.color(contact), 128, stringResource(R.string.call), label = stringResource(R.string.call)) {
                    Calling.call(context, contact)
                }
            }
            RoundIconButton(Icons.Filled.CameraAlt, AppColors.Blue, 96, stringResource(R.string.send_picture), label = stringResource(R.string.send_picture)) {
                val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", photoTarget)
                camera.launch(uri)
            }
        }
        if (contact.usesWhatsApp && WhatsApp.isInstalled(context)) {
            WhatsAppRow(contact, contact.id in whatsAppUnread, go)
        }
    }
}

private const val MESSAGES_SHOWN = 10

/** On a face without a photo: one tap, then camera or gallery. It becomes their contact photo. */
@Composable
private fun AddPhoto(contact: Contact, modifier: Modifier) {
    val picker = rememberPhotoPicker(contact)
    var choosing by remember(contact.id) { mutableStateOf(false) }
    if (!choosing) {
        RoundIconButton(Icons.Filled.AddAPhoto, AppColors.Green, 112, stringResource(R.string.add_photo), modifier, label = stringResource(R.string.add_photo), labelColor = Color.White) {
            choosing = true
        }
    } else {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(Icons.Filled.CameraAlt, AppColors.Green, 104, stringResource(R.string.camera), label = stringResource(R.string.camera), labelColor = Color.White) {
                choosing = false
                picker.camera()
            }
            RoundIconButton(Icons.Filled.PhotoLibrary, AppColors.Blue, 104, stringResource(R.string.gallery), label = stringResource(R.string.gallery), labelColor = Color.White) {
                choosing = false
                picker.gallery()
            }
            RoundIconButton(Icons.Filled.Close, AppColors.Dark, 72, stringResource(R.string.cancel)) { choosing = false }
        }
    }
}

@Composable
private fun WhatsAppRow(contact: Contact, unread: Boolean, go: (Screen) -> Unit) {
    val context = LocalContext.current
    val icon = remember { WhatsApp.icon(context)?.asImageBitmap() }
    val glow = if (unread) pulse() else 0f
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (icon != null) {
            Image(
                icon, "WhatsApp",
                Modifier.size(80.dp).clip(CircleShape)
                    .border(6.dp, AppColors.Green.copy(alpha = glow), CircleShape)
                    .forgivingTap { WhatsApp.openChat(context, contact) },
            )
        }
        // When WhatsApp is already this person's main call button, a second one would only confuse.
        if (Calling.route(contact) == CallRoute.PHONE) {
            RoundIconButton(Icons.Filled.Call, AppColors.WhatsApp, 72, stringResource(R.string.whatsapp_call), label = stringResource(R.string.call)) {
                WhatsApp.startCall(context, contact, video = false)
            }
        }
        RoundIconButton(Icons.Filled.Videocam, AppColors.WhatsApp, 72, stringResource(R.string.whatsapp_video), label = stringResource(R.string.video)) {
            WhatsApp.startCall(context, contact, video = true)
        }
        RoundIconButton(Icons.Filled.Mic, AppColors.WhatsApp, 72, stringResource(R.string.whatsapp_message), label = stringResource(R.string.message)) {
            go(Screen.Record(contact.id, viaWhatsApp = true))
        }
    }
}

@Composable
private fun MessageTile(item: InboxItem, onTap: () -> Unit) {
    val glow = if (!item.seen) pulse() else 0f
    val shape = RoundedCornerShape(16.dp)
    val modifier = Modifier.size(96.dp).clip(shape).border(6.dp, AppColors.Green.copy(alpha = glow), shape)
        .forgivingTap(onTap)
    val picture = if (item.kind == MessageKind.PICTURE && item.fileName != null) {
        rememberImage(Repo.inboxFile(item.fileName))
    } else {
        null
    }
    if (picture != null) {
        Image(picture, "Picture", modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(AppColors.Blue), contentAlignment = Alignment.Center) {
            val icon = when (item.kind) {
                MessageKind.TEXT -> Icons.Filled.VolumeUp
                MessageKind.VIDEO -> Icons.Filled.Videocam
                else -> Icons.Filled.PlayArrow
            }
            Icon(icon, "Play message", tint = Color.White, modifier = Modifier.size(56.dp))
        }
    }
}

// ---- Recording ----

private const val WHATSAPP_MAX_SECONDS = 120

@Composable
private fun RecordScreen(contact: Contact, viaWhatsApp: Boolean, go: (Screen) -> Unit) {
    val context = LocalContext.current
    val format = if (viaWhatsApp) VoiceFormat.AAC else VoiceFormat.AMR
    val file = remember { File(context.cacheDir, "voice-${System.currentTimeMillis()}.${format.extension}") }
    val recorder = remember { VoiceRecorder(file, format) }
    val maxSeconds = remember { if (viaWhatsApp) WHATSAPP_MAX_SECONDS else MmsClient.maxVoiceSeconds() }
    var progress by remember { mutableFloatStateOf(0f) }
    var finished by remember { mutableStateOf(false) }

    val finish: () -> Unit = {
        if (!finished) {
            finished = true
            val ok = recorder.stop()
            when {
                !ok -> go(Screen.Person(contact.id))
                viaWhatsApp -> {
                    WhatsApp.shareVoice(context, contact, file)
                    go(Screen.Person(contact.id))
                }
                else -> go(Screen.Sending(contact.id, file, isPicture = false))
            }
        }
    }

    LaunchedEffect(Unit) {
        Speaker.stop()
        beep()
        delay(350)
        if (!recorder.start()) {
            finished = true
            go(Screen.Person(contact.id))
            return@LaunchedEffect
        }
        val total = maxSeconds * 1000L
        var elapsed = 0L
        while (elapsed < total && !finished) {
            delay(100)
            elapsed += 100
            progress = elapsed.toFloat() / total
        }
        finish()
    }
    DisposableEffect(Unit) {
        onDispose {
            if (!finished) {
                finished = true
                recorder.stop()
                file.delete()
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        FaceImage(contact, Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(26.dp)))
        LinearProgressIndicator(
            progress = { progress },
            Modifier.fillMaxWidth().height(16.dp),
            color = AppColors.Red,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom,
        ) {
            RoundIconButton(Icons.Filled.Close, AppColors.Grey, 88, stringResource(R.string.cancel), label = stringResource(R.string.cancel)) { go(Screen.Person(contact.id)) }
            RoundIconButton(Icons.Filled.Stop, AppColors.Red, 140, stringResource(R.string.send), label = stringResource(R.string.send), onTap = finish)
        }
    }
}

// ---- Sending ----

private const val SEND_TIMEOUT_MILLIS = 120_000L

@Composable
private fun SendingScreen(contact: Contact, screen: Screen.Sending, go: (Screen) -> Unit) {
    val context = LocalContext.current
    val status by MmsClient.sendStatus.collectAsState()
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(attempt) {
        MmsClient.sendStatus.value = SendStatus.SENDING
        if (screen.isPicture) {
            MmsClient.sendPicture(context, contact.phone, screen.file)
        } else {
            MmsClient.sendVoice(context, contact.phone, screen.file)
        }
        delay(SEND_TIMEOUT_MILLIS)
        if (MmsClient.sendStatus.value == SendStatus.SENDING) MmsClient.sendStatus.value = SendStatus.FAILED
    }
    LaunchedEffect(status) {
        if (status == SendStatus.SENT) {
            delay(1500)
            screen.file.delete()
            go(Screen.Person(contact.id))
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        FaceImage(contact, Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(26.dp)))
        Box(Modifier.height(160.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (status) {
                SendStatus.SENT -> RoundIconButton(Icons.Filled.Check, AppColors.Green, 120, stringResource(R.string.sent), label = stringResource(R.string.sent)) {
                    go(Screen.Person(contact.id))
                }
                SendStatus.FAILED -> Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    RoundIconButton(Icons.Filled.Close, AppColors.Red, 96, stringResource(R.string.give_up), label = stringResource(R.string.give_up)) {
                        screen.file.delete()
                        go(Screen.Person(contact.id))
                    }
                    RoundIconButton(Icons.Filled.Refresh, AppColors.Green, 120, stringResource(R.string.try_again), label = stringResource(R.string.try_again)) { attempt += 1 }
                }
                else -> CircularProgressIndicator(Modifier.size(120.dp), strokeWidth = 12.dp)
            }
        }
    }
}

// ---- Viewing a message ----

@Composable
private fun ViewerScreen(screen: Screen.Viewer, go: (Screen) -> Unit) {
    val context = LocalContext.current
    val inbox by Repo.inbox.collectAsState()
    val item = inbox.firstOrNull { it.id == screen.itemId }
    val close = { go(Screen.Person(screen.contactId)) }
    if (item == null) {
        LaunchedEffect(Unit) { close() }
        return
    }
    val file = item.fileName?.let { Repo.inboxFile(it) }
    val playAgain: () -> Unit = {
        when (item.kind) {
            MessageKind.VOICE -> file?.let { Speaker.play(it) }
            MessageKind.TEXT -> item.text?.let { Speaker.say(context, it) }
            MessageKind.VIDEO -> file?.let {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", it)
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/*")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    )
                } catch (_: Exception) {
                    // No video player on the phone.
                }
            }
            MessageKind.PICTURE -> Unit
        }
    }
    DisposableEffect(item.id) {
        Repo.markSeen(item.id)
        playAgain()
        onDispose { Speaker.stop() }
    }
    val contact = Repo.contact(screen.contactId)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val picture = if (item.kind == MessageKind.PICTURE && file != null) rememberImage(file) else null
        if (picture != null) {
            Image(picture, "Picture", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            FaceImage(contact, Modifier.fillMaxSize())
        }
        if (item.kind == MessageKind.TEXT && item.text != null) {
            Text(
                item.text,
                Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.7f)).padding(20.dp),
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 40.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom,
        ) {
            RoundIconButton(Icons.Filled.ArrowBack, AppColors.Dark, 96, stringResource(R.string.back), label = stringResource(R.string.back), labelColor = Color.White, onTap = close)
            if (item.kind != MessageKind.PICTURE) {
                RoundIconButton(Icons.Filled.PlayArrow, AppColors.Green, 140, stringResource(R.string.play), label = stringResource(R.string.play), labelColor = Color.White, onTap = playAgain)
            }
        }
    }
}
