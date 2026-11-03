package com.family.grandmaphone

import com.family.grandmaphone.mms.MmsFormatException
import com.family.grandmaphone.mms.MmsPart
import com.family.grandmaphone.mms.MmsPdu
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream

class MmsPduTest {
    private fun bytes(vararg values: Any): ByteArray {
        val out = ByteArrayOutputStream()
        for (value in values) {
            when (value) {
                is Int -> out.write(value)
                is String -> {
                    out.write(value.toByteArray())
                    out.write(0)
                }
                is ByteArray -> out.write(value)
            }
        }
        return out.toByteArray()
    }

    @Test
    fun sendRequestHasExpectedStructure() {
        val data = byteArrayOf(1, 2, 3)
        val pdu = MmsPdu.encodeSendRequest("T1", "+90 532 123", MmsPart("audio/amr", data), "voice.amr")
        val expected = bytes(
            0x8C, 0x80,
            0x98, "T1",
            0x8D, 0x92,
            0x89, 1, 0x81,
            0x97, "+90532123/TYPE=PLMN",
            0x84, 0xA3,
            1, // one part
        )
        assertEquals(MmsPdu.TYPE_SEND_REQ, MmsPdu.messageType(pdu))
        assertArrayEquals(expected, pdu.copyOf(expected.size))
        assertArrayEquals(data, pdu.copyOfRange(pdu.size - 3, pdu.size))
    }

    @Test
    fun uintvarLengthsOverOneByteAreEncoded() {
        val data = ByteArray(300) { 7 }
        val pdu = MmsPdu.encodeSendRequest("T", "123", MmsPart("audio/amr", data), "v.amr")
        // 300 encodes as 0x82 0x2C immediately before the part's headers.
        val marker = pdu.indices.first { pdu[it] == 0x82.toByte() && pdu[it + 1] == 0x2C.toByte() }
        assertEquals(true, marker > 0)
        assertArrayEquals(data, pdu.copyOfRange(pdu.size - 300, pdu.size))
    }

    @Test
    fun notifyResponse() {
        assertArrayEquals(
            bytes(0x8C, 0x83, 0x98, "ABC", 0x8D, 0x92, 0x95, 0x81),
            MmsPdu.encodeNotifyResponse("ABC"),
        )
    }

    @Test
    fun parsesNotificationSkippingOtherHeaders() {
        val from = "+905321234567"
        val pdu = bytes(
            0x8C, 0x82,
            0x98, "tx-1",
            0x8D, 0x92,
            0x89, 1 + from.length + 1, 0x80, from, // From: length, address-present, text
            0x96, "Subject", // text value
            0x8A, 0x80, // message class
            0x8E, 2, 0x12, 0x34, // message size as a long integer
            0x88, 5, 0x81, 3, 1, 2, 3, // expiry
            0x83, "http://mmsc.example/abc",
        )
        val parsed = MmsPdu.parseNotification(pdu)
        assertEquals("tx-1", parsed.transactionId)
        assertEquals("http://mmsc.example/abc", parsed.contentLocation)
        assertEquals(from, parsed.from)
    }

    @Test
    fun stripsAddressTypeAndHandlesHiddenSender() {
        val withType = "+905321234567/TYPE=PLMN"
        val pdu = bytes(
            0x8C, 0x82, 0x98, "t",
            0x89, 1 + withType.length + 1, 0x80, withType,
            0x83, "http://x",
        )
        assertEquals("+905321234567", MmsPdu.parseNotification(pdu).from)

        val hidden = bytes(0x8C, 0x82, 0x98, "t", 0x89, 1, 0x81, 0x83, "http://x")
        assertNull(MmsPdu.parseNotification(hidden).from)
    }

    @Test
    fun readsAddressWithCharset() {
        val address = "+905321234567/TYPE=PLMN"
        val encoded = bytes(address.length + 2, 0xEA, address)
        val pdu = bytes(
            0x8C, 0x82, 0x98, "t",
            0x89, 1 + encoded.size, 0x80, encoded,
            0x83, "http://x",
        )
        assertEquals("+905321234567", MmsPdu.parseNotification(pdu).from)
    }

    @Test
    fun parsesMultipartWithSmilImageAndText() {
        val smil = "<smil/>".toByteArray()
        val image = byteArrayOf(9, 8, 7)
        val text = "Merhaba çiçek".toByteArray()
        val smilHeaders = bytes(17, "application/smil")
        val imageHeaders = bytes(0x9E, 0x8E, "a.jpg")
        val textHeaders = bytes(3, 0x83, 0x81, 0xEA)
        val from = "+905321234567/TYPE=PLMN"
        val pdu = bytes(
            0x8C, 0x84,
            0x98, "t",
            0x89, 1 + from.length + 1, 0x80, from,
            0x85, 2, 0x01, 0x02, // date
            0x84, 6, 0xB3, 0x89, "abc", // multipart/related with a parameter
            3,
            smilHeaders.size, smil.size, smilHeaders, smil,
            imageHeaders.size, image.size, imageHeaders, image,
            textHeaders.size, text.size, textHeaders, text,
        )
        val parsed = MmsPdu.parseRetrieved(pdu)
        assertEquals("+905321234567", parsed.from)
        assertEquals(listOf("application/smil", "image/jpeg", "text/plain"), parsed.parts.map { it.contentType })
        assertArrayEquals(image, parsed.parts[1].data)
        assertEquals("Merhaba çiçek", parsed.parts[2].text)
    }

    @Test
    fun readsWhatItBuilds() {
        val data = ByteArray(500) { (it % 251).toByte() }
        val sent = MmsPdu.encodeSendRequest("tx", "+905321234567", MmsPart("audio/amr", data), "voice.amr")
        // A retrieved message has the same body, with only the type byte changed.
        val received = sent.copyOf().also { it[1] = MmsPdu.TYPE_RETRIEVE_CONF.toByte() }
        val parsed = MmsPdu.parseRetrieved(received)
        assertEquals("audio/amr", parsed.parts.single().contentType)
        assertArrayEquals(data, parsed.parts.single().data)
    }

    @Test
    fun malformedDataIsRejected() {
        val attempts = listOf(
            byteArrayOf(),
            bytes(0x8C, 0x82),
            bytes(0x8C, 0x82, 0x98, 65, 66), // unterminated text
            bytes(0x8C, 0x82, 0x98, "t", 0x89, 30, 0x80), // length past the end
        )
        for (pdu in attempts) {
            assertThrows(MmsFormatException::class.java) { MmsPdu.parseNotification(pdu) }
        }
        assertThrows(MmsFormatException::class.java) {
            MmsPdu.parseRetrieved(bytes(0x8C, 0x84, 0x84, 0xA3, 1, 200, 200)) // part larger than the data
        }
    }

    @Test
    fun manyMalformedInputsNeverThrowAnythingElse() {
        val random = java.util.Random(42)
        val base = MmsPdu.encodeSendRequest("t", "123", MmsPart("audio/amr", ByteArray(40)), "v.amr")
            .also { it[1] = MmsPdu.TYPE_RETRIEVE_CONF.toByte() }
        repeat(5000) {
            val mutated = base.copyOf(1 + random.nextInt(base.size))
            repeat(random.nextInt(6)) { mutated[random.nextInt(mutated.size)] = random.nextInt(256).toByte() }
            mutated[0] = 0x8C.toByte()
            if (mutated.size > 1) mutated[1] = MmsPdu.TYPE_RETRIEVE_CONF.toByte()
            try {
                MmsPdu.parseRetrieved(mutated)
            } catch (_: MmsFormatException) {
                // Expected for bad input.
            }
        }
    }
}
