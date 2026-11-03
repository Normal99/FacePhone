package com.family.grandmaphone.call

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.family.grandmaphone.R
import com.family.grandmaphone.ui.AppColors
import com.family.grandmaphone.ui.AppLocale
import com.family.grandmaphone.ui.FaceImage
import com.family.grandmaphone.ui.Notifier
import com.family.grandmaphone.ui.RoundIconButton

/** The full-screen call display: a face and big buttons, with the name and short labels as a fallback. */
class CallActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            val state by CallManager.ui.collectAsState()
            LaunchedEffect(state == null) {
                if (state == null) finish()
            }
            state?.let { CallScreen(it) }
        }
    }

    /** The notification is only there to bring this screen up. Left showing, it would cover the top. */
    override fun onResume() {
        super.onResume()
        Notifier.cancelIncomingCall(this)
    }
}

@androidx.compose.runtime.Composable
private fun CallScreen(state: CallUi) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        FaceImage(state.contact, Modifier.fillMaxSize())
        Text(
            state.contact?.name ?: state.number.ifBlank { stringResource(R.string.unknown_caller) },
            Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.6f)).padding(16.dp),
            color = Color.White,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 48.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom,
        ) {
            when (state.phase) {
                CallPhase.RINGING -> {
                    RoundIconButton(Icons.Filled.CallEnd, AppColors.Red, 120, stringResource(R.string.decline), label = stringResource(R.string.decline), labelColor = Color.White) { CallManager.endOrDecline() }
                    RoundIconButton(Icons.Filled.Call, AppColors.Green, 140, stringResource(R.string.answer), label = stringResource(R.string.answer), labelColor = Color.White) { CallManager.answer() }
                }
                else -> {
                    RoundIconButton(
                        if (state.speaker) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                        AppColors.Blue, 96, stringResource(R.string.speaker),
                        label = stringResource(R.string.speaker), labelColor = Color.White,
                    ) { CallManager.toggleSpeaker() }
                    RoundIconButton(Icons.Filled.CallEnd, AppColors.Red, 140, stringResource(R.string.hang_up), label = stringResource(R.string.hang_up), labelColor = Color.White) { CallManager.endOrDecline() }
                }
            }
        }
    }
}
