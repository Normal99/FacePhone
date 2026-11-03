package com.family.grandmaphone.call

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.family.grandmaphone.ui.AppColors
import com.family.grandmaphone.ui.FacesTheme
import com.family.grandmaphone.ui.RoundIconButton

/** A plain keypad. Android requires the default phone app to have one. It's not on her screens. */
class DialActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val start = intent.data?.schemeSpecificPart.orEmpty()
        setContent {
            FacesTheme {
                var number by remember { mutableStateOf(start) }
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(number, fontSize = 32.sp)
                        IconButton(onClick = { number = number.dropLast(1) }) {
                            Icon(Icons.Filled.Backspace, "Delete")
                        }
                    }
                    for (row in listOf("123", "456", "789", "*0#")) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            for (key in row) {
                                TextButton(onClick = { number += key }, Modifier.size(84.dp)) {
                                    Text(key.toString(), fontSize = 32.sp)
                                }
                            }
                        }
                    }
                    RoundIconButton(Icons.Filled.Call, AppColors.Green, 96, "Call") {
                        if (number.isNotBlank()) {
                            CallManager.place(this@DialActivity, number)
                            finish()
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }
}
