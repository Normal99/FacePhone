package com.family.grandmaphone.mms

import com.family.grandmaphone.data.MessageKind

object MmsRouting {
    /** What a part of an MMS is. Null for parts she doesn't need, like layout information. */
    fun kindOf(contentType: String): MessageKind? {
        val type = contentType.lowercase()
        return when {
            type.startsWith("audio/") -> MessageKind.VOICE
            type.startsWith("image/") -> MessageKind.PICTURE
            type.startsWith("video/") -> MessageKind.VIDEO
            type == "text/plain" -> MessageKind.TEXT
            else -> null
        }
    }

    /** A fixed extension for a saved part. Names sent by the other side are never used for files. */
    fun extensionOf(contentType: String): String = when (contentType.lowercase()) {
        "audio/amr" -> "amr"
        "audio/mp4", "audio/m4a", "audio/aac", "audio/x-m4a" -> "m4a"
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/ogg" -> "ogg"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "video/mp4" -> "mp4"
        "video/3gpp" -> "3gp"
        else -> "bin"
    }

    /** How long a voice note fits in the carrier's size limit, at AMR's 1.5 KB per second. */
    fun maxVoiceSeconds(maxMessageBytes: Int): Int {
        val overhead = 2048
        return ((maxMessageBytes - overhead) / 1500).coerceIn(15, 120)
    }
}
