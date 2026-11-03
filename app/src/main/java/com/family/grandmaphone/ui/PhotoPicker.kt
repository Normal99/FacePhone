package com.family.grandmaphone.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.family.grandmaphone.data.Contact
import com.family.grandmaphone.data.ContactPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Takes or picks a photo for a person and saves it as their contact photo. */
class PhotoPicker(val camera: () -> Unit, val gallery: () -> Unit)

@Composable
fun rememberPhotoPicker(contact: Contact): PhotoPicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shot = remember(contact.id) { File(context.cacheDir, "contact-photo.jpg") }
    val shotUri: Uri = remember(contact.id) { FileProvider.getUriForFile(context, "${context.packageName}.files", shot) }
    val save = { source: Uri ->
        scope.launch { withContext(Dispatchers.IO) { ContactPhoto.set(context.applicationContext, contact, source) } }
        Unit
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        if (taken) save(shotUri)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { picked ->
        if (picked != null) save(picked)
    }
    return remember(contact.id) { PhotoPicker({ camera.launch(shotUri) }, { gallery.launch("image/*") }) }
}
