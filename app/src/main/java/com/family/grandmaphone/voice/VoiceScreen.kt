package com.family.grandmaphone.voice

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.family.grandmaphone.R
import com.family.grandmaphone.call.Calling
import com.family.grandmaphone.data.CallRoute
import com.family.grandmaphone.whatsapp.WhatsApp
import com.family.grandmaphone.data.Contact
import com.family.grandmaphone.data.Repo
import com.family.grandmaphone.media.Speaker
import com.family.grandmaphone.media.SpeechCapture
import com.family.grandmaphone.media.beep
import com.family.grandmaphone.ui.AppColors
import com.family.grandmaphone.ui.FaceImage
import com.family.grandmaphone.ui.RoundIconButton
import com.family.grandmaphone.ui.forgivingTap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private sealed interface VoiceState {
    data object Listening : VoiceState
    data class Thinking(val heard: String) : VoiceState
    data class Result(val result: VoiceResult, val heard: String) : VoiceState
    data object NotHeard : VoiceState
}

/** Local matching answers first. The AI model is only asked when that isn't sure. */
private suspend fun work(context: Context, heard: List<String>, contacts: List<Contact>): VoiceResult {
    val local = VoiceMatcher.match(heard, contacts)
    if (local is VoiceResult.Found) return local
    val settings = Repo.settings.value
    if (settings.voiceApiKey.isBlank() || !isOnline(context)) return local
    return try {
        withContext(Dispatchers.IO) { VoiceResolver.resolveText(settings.voiceApiKey, settings.voiceModel, heard, contacts) }
    } catch (_: Exception) {
        // With no answer from the model, the phone's own guesses are still worth showing.
        local
    }
}

private fun isOnline(context: Context): Boolean {
    val network = context.getSystemService(ConnectivityManager::class.java)
    val capabilities = network.getNetworkCapabilities(network.activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

private fun listen(
    context: Context,
    onPartial: (String) -> Unit,
    onHeard: (List<String>) -> Unit,
    onFailed: () -> Unit,
): SpeechRecognizer? {
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
        onFailed()
        return null
    }
    val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
    recognizer.setRecognitionListener(object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            if (heard.isEmpty()) onFailed() else onHeard(heard)
        }

        override fun onPartialResults(partial: Bundle?) {
            partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onPartial)
        }

        override fun onError(error: Int) = onFailed()
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    })
    val tag = Repo.settings.value.speechLanguage.ifBlank { Locale.getDefault().toLanguageTag() }
    recognizer.startListening(
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, !isOnline(context))
    )
    return recognizer
}

@Composable
fun VoiceScreen(onClose: () -> Unit, onMessage: (Contact) -> Unit) {
    val context = LocalContext.current
    var state by remember { mutableStateOf<VoiceState>(VoiceState.Listening) }
    var partial by remember { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }

    var heard by remember { mutableStateOf<List<String>>(emptyList()) }
    // The model hears her directly if the family turned that on. Any failure drops back to the
    // phone's recognizer for the rest of this visit, so she can always still be understood.
    var hearDirectly by remember {
        val settings = Repo.settings.value
        mutableStateOf(settings.voiceHearsAudio && settings.voiceApiKey.isNotBlank() && isOnline(context))
    }

    LaunchedEffect(attempt) {
        if (!hearDirectly) return@LaunchedEffect
        Speaker.stop()
        partial = ""
        state = VoiceState.Listening
        val wav = SpeechCapture.record()
        if (wav == null) {
            // The beep tells her to say it again, this time to the phone's recognizer.
            beep()
            hearDirectly = false
            attempt += 1
            return@LaunchedEffect
        }
        state = VoiceState.Thinking("")
        val settings = Repo.settings.value
        val people = Repo.contacts.value.filterNot { it.hidden }
        try {
            val result = withContext(Dispatchers.IO) {
                VoiceResolver.resolveAudio(settings.voiceApiKey, settings.voiceModel, wav, people)
            }
            state = VoiceState.Result(result, "")
        } catch (_: Exception) {
            beep()
            hearDirectly = false
            attempt += 1
        }
    }
    DisposableEffect(attempt, hearDirectly) {
        if (hearDirectly) return@DisposableEffect onDispose {}
        Speaker.stop()
        partial = ""
        state = VoiceState.Listening
        val recognizer = listen(
            context,
            onPartial = { partial = it },
            onHeard = {
                heard = it
                state = VoiceState.Thinking(it.first())
            },
            onFailed = {
                // With no internet the phone may not understand her language at all, so she picks a face instead.
                val people = Repo.visibleContacts(Repo.contacts.value, Repo.settings.value)
                state = if (!isOnline(context) && people.isNotEmpty()) {
                    VoiceState.Result(VoiceResult.Choose(people, VoiceAction.CALL), "")
                } else {
                    VoiceState.NotHeard
                }
            },
        )
        onDispose { recognizer?.destroy() }
    }
    LaunchedEffect(heard) {
        if (heard.isEmpty()) return@LaunchedEffect
        val people = Repo.contacts.value.filterNot { it.hidden }
        state = VoiceState.Result(work(context, heard, people), heard.first())
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (val current = state) {
            VoiceState.Listening -> Centered {
                RoundIconButton(Icons.Filled.Mic, AppColors.Red, 180, stringResource(R.string.listening)) {}
                Big(stringResource(R.string.say_who))
                Big(partial)
            }
            is VoiceState.Thinking -> Centered {
                CircularProgressIndicator(Modifier.size(120.dp), strokeWidth = 12.dp)
                Big(current.heard)
            }
            VoiceState.NotHeard -> Centered {
                Big(stringResource(R.string.didnt_catch))
            }
            is VoiceState.Result -> when (val result = current.result) {
                is VoiceResult.Found -> Confirm(result.contact, result.action, onClose, onMessage)
                is VoiceResult.Choose ->
                    if (result.contacts.size == 1) {
                        Confirm(result.contacts.first(), result.action, onClose, onMessage)
                    } else {
                        // She picks a face, then chooses a regular or WhatsApp call on the next screen.
                        var picked by remember(result) { mutableStateOf<Contact?>(null) }
                        val person = picked
                        if (person != null) Confirm(person, result.action, onClose, onMessage)
                        else Choose(result, onClose) { picked = it }
                    }
                VoiceResult.NotFound -> Centered {
                    Big(stringResource(R.string.didnt_find))
                    Big(current.heard)
                }
            }
        }
        val needsRetry = state is VoiceState.NotHeard ||
            (state as? VoiceState.Result)?.result is VoiceResult.NotFound
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            if (needsRetry) {
                RoundIconButton(Icons.Filled.Mic, AppColors.Green, 112, stringResource(R.string.try_again), label = stringResource(R.string.try_again)) {
                    heard = emptyList()
                    attempt += 1
                }
            }
            if (state !is VoiceState.Result || needsRetry) {
                RoundIconButton(Icons.Filled.ArrowBack, AppColors.Dark, 112, stringResource(R.string.back), label = stringResource(R.string.back), onTap = onClose)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.weight(1f).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
    ) { content() }
}

@Composable
private fun Big(text: String) {
    Text(text, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
}

/** Shows and says who it found. Only her tap places the call. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Confirm(
    contact: Contact,
    action: VoiceAction,
    onClose: () -> Unit,
    onMessage: (Contact) -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(contact.id) {
        val clip = Repo.nameClipFile(contact.id)
        if (clip.exists()) Speaker.play(clip) else Speaker.say(context, contact.name)
    }
    FaceImage(contact, Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(26.dp)))
    Big(contact.name)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Bottom,
    ) {
        RoundIconButton(Icons.Filled.Close, AppColors.Red, 112, stringResource(R.string.cancel), label = stringResource(R.string.cancel), onTap = onClose)
        if (action == VoiceAction.MESSAGE) {
            RoundIconButton(Icons.Filled.Mic, AppColors.Blue, 148, stringResource(R.string.message), label = stringResource(R.string.message)) {
                onMessage(contact)
            }
        } else {
            val offerWhatsApp = Calling.route(contact) == CallRoute.PHONE && WhatsApp.isInstalled(context)
            // Two call buttons and cancel have to fit across a phone screen.
            val size = if (offerWhatsApp) 120 else 148
            if (Calling.canCall(context, contact)) {
                RoundIconButton(Icons.Filled.Call, Calling.color(contact), size, stringResource(R.string.call), label = stringResource(R.string.call)) {
                    if (Calling.call(context, contact)) onClose()
                }
            }
            // A regular call can also go over WhatsApp, her choice.
            if (offerWhatsApp) {
                RoundIconButton(Icons.Filled.Call, AppColors.WhatsApp, size, stringResource(R.string.whatsapp_call), label = "WhatsApp") {
                    if (WhatsApp.startCall(context, contact, video = false)) onClose()
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Choose(
    result: VoiceResult.Choose,
    onClose: () -> Unit,
    onPick: (Contact) -> Unit,
) {
    Big(stringResource(R.string.who_do_you_mean))
    LazyVerticalGrid(
        GridCells.Fixed(2),
        Modifier.weight(1f),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(result.contacts, key = { it.id }) { contact ->
            Box(
                Modifier.aspectRatio(1f).clip(RoundedCornerShape(26.dp)).forgivingTap { onPick(contact) }
            ) {
                FaceImage(contact, Modifier.fillMaxSize())
                Text(
                    contact.name,
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)).padding(6.dp),
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
        }
    }
    RoundIconButton(Icons.Filled.Close, AppColors.Red, 112, stringResource(R.string.cancel), label = stringResource(R.string.cancel), onTap = onClose)
}
