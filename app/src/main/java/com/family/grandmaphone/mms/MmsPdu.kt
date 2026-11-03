package com.family.grandmaphone.mms

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/** One attachment of an MMS. */
class MmsPart(val contentType: String, val data: ByteArray, val charset: Charset = Charsets.UTF_8) {
    val text: String get() = String(data, charset)
}

class MmsNotification(val transactionId: String, val contentLocation: String, val from: String?)

class MmsMessage(val from: String?, val parts: List<MmsPart>)

/**
 * The small part of the MMS binary format (OMA MMS encapsulation, WAP-209) this app needs:
 * building a message to send, and reading notifications and downloaded messages.
 * Parsers throw [MmsFormatException] on malformed input, which comes from the network.
 */
object MmsPdu {
    private const val H_CONTENT_LOCATION = 0x83
    private const val H_CONTENT_TYPE = 0x84
    private const val H_FROM = 0x89
    private const val H_MESSAGE_TYPE = 0x8C
    private const val H_VERSION = 0x8D
    private const val H_STATUS = 0x95
    private const val H_TO = 0x97
    private const val H_TRANSACTION_ID = 0x98

    const val TYPE_SEND_REQ = 0x80
    const val TYPE_NOTIFICATION_IND = 0x82
    const val TYPE_NOTIFY_RESP_IND = 0x83
    const val TYPE_RETRIEVE_CONF = 0x84

    private const val VERSION_1_2 = 0x92
    private const val STATUS_RETRIEVED = 0x81
    private const val INSERT_ADDRESS_TOKEN = 0x81
    private const val ADDRESS_PRESENT_TOKEN = 0x80
    private const val MULTIPART_MIXED = 0xA3
    private const val PART_CONTENT_LOCATION = 0x8E
    private const val PARAM_CHARSET = 0x81
    private const val PARAM_NAME = 0x85

    private val WELL_KNOWN_TYPES = mapOf(
        0x00 to "*/*",
        0x03 to "text/plain",
        0x1D to "image/gif",
        0x1E to "image/jpeg",
        0x20 to "image/png",
        0x21 to "image/vnd.wap.wbmp",
        0x22 to "application/vnd.wap.multipart.*",
        0x23 to "application/vnd.wap.multipart.mixed",
        0x33 to "application/vnd.wap.multipart.related",
    )

    private val WELL_KNOWN_CHARSETS = mapOf(
        3 to Charsets.US_ASCII,
        4 to Charsets.ISO_8859_1,
        106 to Charsets.UTF_8,
        1015 to Charsets.UTF_16,
    )

    // ---- Building ----

    fun encodeSendRequest(
        transactionId: String,
        toPhoneNumber: String,
        part: MmsPart,
        fileName: String,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(H_MESSAGE_TYPE)
        out.write(TYPE_SEND_REQ)
        out.write(H_TRANSACTION_ID)
        out.writeText(transactionId)
        out.write(H_VERSION)
        out.write(VERSION_1_2)
        out.write(H_FROM)
        out.write(1)
        out.write(INSERT_ADDRESS_TOKEN)
        out.write(H_TO)
        out.writeText(toPhoneNumber.filter { it.isDigit() || it == '+' } + "/TYPE=PLMN")
        out.write(H_CONTENT_TYPE)
        out.write(MULTIPART_MIXED)

        val headers = ByteArrayOutputStream()
        val contentType = ByteArrayOutputStream()
        contentType.writeText(part.contentType)
        contentType.write(PARAM_NAME)
        contentType.writeText(fileName)
        headers.writeValueLength(contentType.size())
        contentType.writeTo(headers)
        headers.write(PART_CONTENT_LOCATION)
        headers.writeText(fileName)

        out.writeUintvar(1)
        out.writeUintvar(headers.size())
        out.writeUintvar(part.data.size)
        headers.writeTo(out)
        out.write(part.data)
        return out.toByteArray()
    }

    /** Tells the carrier a message has been fetched, so it isn't announced again. */
    fun encodeNotifyResponse(transactionId: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(H_MESSAGE_TYPE)
        out.write(TYPE_NOTIFY_RESP_IND)
        out.write(H_TRANSACTION_ID)
        out.writeText(transactionId)
        out.write(H_VERSION)
        out.write(VERSION_1_2)
        out.write(H_STATUS)
        out.write(STATUS_RETRIEVED)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeText(value: String) {
        write(value.toByteArray(Charsets.UTF_8))
        write(0)
    }

    private fun ByteArrayOutputStream.writeValueLength(length: Int) {
        if (length <= 30) {
            write(length)
        } else {
            write(31)
            writeUintvar(length)
        }
    }

    private fun ByteArrayOutputStream.writeUintvar(value: Int) {
        var shift = 28
        while (shift > 0 && (value ushr shift) == 0) shift -= 7
        while (shift > 0) {
            write(((value ushr shift) and 0x7F) or 0x80)
            shift -= 7
        }
        write(value and 0x7F)
    }

    // ---- Reading ----

    /** The message's type, or -1 when the data has none. */
    fun messageType(pdu: ByteArray): Int {
        if (pdu.size < 2 || (pdu[0].toInt() and 0xFF) != H_MESSAGE_TYPE) return -1
        return pdu[1].toInt() and 0xFF
    }

    fun parseNotification(pdu: ByteArray): MmsNotification {
        if (messageType(pdu) != TYPE_NOTIFICATION_IND) throw MmsFormatException("not a notification")
        val reader = Reader(pdu)
        var transactionId: String? = null
        var location: String? = null
        var from: String? = null
        while (reader.hasMore) {
            when (val header = reader.byte()) {
                H_TRANSACTION_ID -> transactionId = reader.textString()
                H_CONTENT_LOCATION -> location = reader.textString()
                H_FROM -> from = reader.fromAddress()
                else -> reader.skipHeaderValue(header)
            }
        }
        if (transactionId == null || location == null) throw MmsFormatException("incomplete notification")
        return MmsNotification(transactionId, location, from)
    }

    fun parseRetrieved(pdu: ByteArray): MmsMessage {
        if (messageType(pdu) != TYPE_RETRIEVE_CONF) throw MmsFormatException("not a retrieved message")
        val reader = Reader(pdu)
        var from: String? = null
        while (reader.hasMore) {
            when (val header = reader.byte()) {
                H_FROM -> from = reader.fromAddress()
                // The content type is always the last header, and the body follows it.
                H_CONTENT_TYPE -> {
                    val type = reader.contentType()
                    val parts = if (type.mediaType.contains("multipart")) {
                        reader.multipart()
                    } else {
                        listOf(MmsPart(type.mediaType, reader.rest(), type.charset))
                    }
                    return MmsMessage(from, parts)
                }
                else -> reader.skipHeaderValue(header)
            }
        }
        throw MmsFormatException("no content")
    }

    private class ContentType(val mediaType: String, val charset: Charset)

    private class Reader(private val data: ByteArray, private var pos: Int = 0, private val end: Int = data.size) {
        val hasMore get() = pos < end

        fun byte(): Int {
            if (pos >= end) throw MmsFormatException("unexpected end")
            return data[pos++].toInt() and 0xFF
        }

        private fun peek(): Int {
            if (pos >= end) throw MmsFormatException("unexpected end")
            return data[pos].toInt() and 0xFF
        }

        private fun skip(count: Int) {
            if (count < 0 || count > end - pos) throw MmsFormatException("bad length")
            pos += count
        }

        fun rest(): ByteArray = data.copyOfRange(pos, end).also { pos = end }

        fun uintvar(): Int {
            var value = 0L
            repeat(5) {
                val b = byte()
                value = (value shl 7) or (b and 0x7F).toLong()
                if (b and 0x80 == 0) {
                    if (value > Int.MAX_VALUE) throw MmsFormatException("number too large")
                    return value.toInt()
                }
            }
            throw MmsFormatException("number too long")
        }

        fun textString(): String {
            val start = pos
            while (pos < end && data[pos].toInt() != 0) pos++
            if (pos >= end) throw MmsFormatException("unterminated text")
            val value = String(data, start, pos - start, Charsets.UTF_8).removePrefix("\u007F")
            pos++
            return value
        }

        private fun valueLength(): Int {
            val first = byte()
            return when {
                first <= 30 -> first
                first == 31 -> uintvar()
                else -> throw MmsFormatException("expected a length")
            }
        }

        fun skipHeaderValue(header: Int) {
            if (header < 0x80) {
                // An application header: its name is text, followed by a text value.
                pos--
                textString()
                textString()
                return
            }
            val first = peek()
            when {
                first <= 31 -> {
                    val length = valueLength()
                    skip(length)
                }
                first < 0x80 -> textString()
                else -> skip(1)
            }
        }

        /** The address in a From header, without its "/TYPE=PLMN" suffix. Null when hidden. */
        fun fromAddress(): String? {
            val length = valueLength()
            val valueEnd = pos + length
            if (length < 1 || valueEnd > end) throw MmsFormatException("bad address")
            val token = byte()
            var address: String? = null
            if (token == ADDRESS_PRESENT_TOKEN && pos < valueEnd) {
                if (peek() <= 31) {
                    // Encoded with a charset: length, charset, then the text.
                    valueLength()
                    if (peek() >= 0x80) skip(1) else integerValue()
                }
                address = textString().substringBefore('/')
            }
            pos = valueEnd
            return address?.takeIf { it.isNotEmpty() }
        }

        private fun integerValue(): Int {
            val first = byte()
            if (first >= 0x80) return first and 0x7F
            if (first > 30) throw MmsFormatException("bad integer")
            if (first > 4) {
                skip(first)
                return -1
            }
            var value = 0
            repeat(first) { value = (value shl 8) or byte() }
            return value
        }

        private fun mediaType(): String {
            val first = peek()
            return if (first >= 0x80) {
                skip(1)
                WELL_KNOWN_TYPES[first and 0x7F] ?: "application/octet-stream"
            } else {
                textString()
            }
        }

        fun contentType(): ContentType {
            val first = peek()
            if (first > 31) return ContentType(mediaType(), Charsets.UTF_8)
            val length = valueLength()
            val valueEnd = pos + length
            if (valueEnd > end) throw MmsFormatException("bad content type")
            val type = mediaType()
            var charset: Charset = Charsets.UTF_8
            // Charset comes first when present. The other parameters aren't needed.
            if (pos < valueEnd && peek() == PARAM_CHARSET) {
                skip(1)
                charset = WELL_KNOWN_CHARSETS[integerValue()] ?: Charsets.UTF_8
            }
            pos = valueEnd
            return ContentType(type, charset)
        }

        fun multipart(): List<MmsPart> {
            val count = uintvar()
            val parts = ArrayList<MmsPart>()
            repeat(count) {
                val headersLength = uintvar()
                val dataLength = uintvar()
                val headersStart = pos
                val type = contentType()
                pos = headersStart
                skip(headersLength)
                val dataStart = pos
                skip(dataLength)
                parts += MmsPart(type.mediaType, data.copyOfRange(dataStart, pos), type.charset)
            }
            return parts
        }
    }
}

class MmsFormatException(message: String) : Exception(message)
