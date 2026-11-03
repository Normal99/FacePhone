package com.family.grandmaphone.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.family.grandmaphone.data.Contact
import com.family.grandmaphone.data.Repo
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

object AppColors {
    val Green = Color(0xFF1B8E3E)
    val Red = Color(0xFFC62828)
    val Blue = Color(0xFF0381FE)
    val WhatsApp = Color(0xFF128C7E)
    val Grey = Color(0xFF8A8A8A)
    val Dark = Color(0xFF3A3A3A)
}

private const val REPEAT_GUARD_MILLIS = 700L
private const val TAP_MOVEMENT_SLOPS = 2

private fun PointerInputChange.isInside(size: IntSize) =
    position.x >= 0 && position.y >= 0 && position.x <= size.width && position.y <= size.height

/**
 * A tap for unsteady hands: a press of any length counts, a second finger or palm touching
 * cancels it, and a quick repeat is ignored so one call isn't placed twice. Scrolling is never a
 * tap. Reading events after the list has had its turn shows whether the list used this touch.
 */
fun Modifier.forgivingTap(onTap: () -> Unit): Modifier = composed {
    // Keyed on Unit so a redraw during a press can't restart the gesture and lose the tap.
    val latest by rememberUpdatedState(onTap)
    pointerInput(Unit) {
        var lastTap = 0L
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            val allowedMovement = viewConfiguration.touchSlop * TAP_MOVEMENT_SLOPS
            var valid = !down.isConsumed
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                if (event.changes.size > 1 || event.changes.any { it.isConsumed }) valid = false
                if ((event.changes.first().position - down.position).getDistance() > allowedMovement) valid = false
                if (event.changes.none { it.pressed }) {
                    val inside = event.changes.first().isInside(size)
                    val now = System.currentTimeMillis()
                    if (valid && inside && now - lastTap > REPEAT_GUARD_MILLIS) {
                        lastTap = now
                        latest()
                    }
                    break
                }
            }
        }
    }
}

/** Every separate tap counts, with no repeat guard. Used for the hidden admin gesture. */
fun Modifier.anyTap(onTap: () -> Unit): Modifier = composed {
    val latest by rememberUpdatedState(onTap)
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.none { it.pressed }) {
                    latest()
                    break
                }
            }
        }
    }
}

@Composable
fun RoundIconButton(
    icon: ImageVector,
    color: Color,
    sizeDp: Int,
    description: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    labelColor: Color = Color.Unspecified,
    onTap: () -> Unit,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(sizeDp.dp).clip(CircleShape).background(color).forgivingTap(onTap),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, description, tint = Color.White, modifier = Modifier.size((sizeDp * 0.55f).dp))
        }
        if (label != null) {
            Text(label, color = labelColor, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1, softWrap = false)
        }
    }
}

/** For serious actions: fills up while held down and only fires when full. */
@Composable
fun HoldButton(
    icon: ImageVector,
    color: Color,
    description: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    holdMillis: Int = 3000,
    onDone: () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onDone)
    Box(
        modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.45f))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var job: Job? = null
                    job = scope.launch {
                        progress.animateTo(1f, tween(holdMillis, easing = LinearEasing))
                        latest()
                    }
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                    }
                    job.cancel()
                    scope.launch { progress.animateTo(0f, tween(200)) }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(progress.value).background(color)
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, description, tint = Color.White, modifier = Modifier.size(56.dp))
            if (label != null) Text(label, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun rememberImage(file: File): ImageBitmap? {
    val version by Repo.filesVersion.collectAsState()
    return remember(file.path, file.lastModified(), version) { loadImage(file) }
}

/** Reads an image at a sensible size. Null if there is none or it can't be read. */
fun loadImage(file: File, maxSide: Int = 1400): ImageBitmap? {
    if (!file.exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
}

/** A person's photo, or a grey silhouette for anyone without one, including strangers. */
@Composable
fun FaceImage(contact: Contact?, modifier: Modifier = Modifier) {
    val image = contact?.let { rememberImage(Repo.photoFile(it.id)) ?: rememberImage(Repo.whatsAppPhotoFile(it.id)) }
    if (image != null) {
        Image(image, contact.name, modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(AppColors.Grey), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Person, "Unknown", tint = Color.White, modifier = Modifier.fillMaxSize(0.7f))
        }
    }
}
