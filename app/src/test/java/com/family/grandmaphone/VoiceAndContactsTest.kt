package com.family.grandmaphone

import com.family.grandmaphone.data.Contact
import com.family.grandmaphone.data.ContactImport
import com.family.grandmaphone.data.PhoneRow
import com.family.grandmaphone.media.SilenceDetector
import com.family.grandmaphone.media.Wav
import com.family.grandmaphone.voice.VoiceAction
import com.family.grandmaphone.voice.VoiceAnswer
import com.family.grandmaphone.voice.VoiceMatcher
import com.family.grandmaphone.voice.VoiceRequest
import com.family.grandmaphone.voice.VoiceResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAndContactsTest {
    private val ali = Contact("a", "Ali Yılmaz", "1", aliases = listOf("oğlum", "my son"))
    private val ayse = Contact("b", "Ayşe Demir", "2", nickname = "Ayşecik")
    private val aliye = Contact("c", "Aliye Kaya", "3")
    private val mehmet = Contact("d", "Mehmet Demir", "4")
    private val all = listOf(ali, ayse, aliye, mehmet)

    private fun found(vararg heard: String): Contact? =
        (VoiceMatcher.match(heard.toList(), all) as? VoiceResult.Found)?.contact

    @Test
    fun plainNamesAreFound() {
        assertEquals(mehmet, found("Mehmet"))
        assertEquals(mehmet, found("call Mehmet please"))
        assertEquals(mehmet, found("Mehmet'i ara"))
    }

    @Test
    fun accentsAndTurkishLettersDontMatter() {
        assertEquals(ayse, found("ayse"))
        assertEquals(ayse, found("AYŞE'yi ara"))
        assertEquals(ali, found("ALI YILMAZ"))
    }

    @Test
    fun turkishCapitalsAndEndingsMatch() {
        assertEquals(ali, found("ALİ'Yİ ARA"))
        assertEquals(ali, found("Ali Yılmaz'ı ara"))
        assertEquals(ayse, found("Ayşe'yi arar mısın"))
        assertEquals(ali, found("OĞLUMU ARA"))
    }

    @Test
    fun softenedTurkishEndingsMatch() {
        assertEquals(mehmet, found("Mehmedi ara"))
        assertEquals(mehmet, found("mehmede mesaj at"))
        val burak = Contact("e", "Burak", "5")
        assertEquals(VoiceResult.Found(burak, VoiceAction.CALL), VoiceMatcher.match(listOf("Burağı ara"), listOf(burak, ali)))
        val recep = Contact("f", "Recep", "6")
        assertEquals(VoiceResult.Found(recep, VoiceAction.CALL), VoiceMatcher.match(listOf("Recebi ara"), listOf(recep, ali)))
    }

    @Test
    fun plainLettersFindTurkishNames() {
        val guzel = Contact("g", "Güzel", "7")
        for (heard in listOf("guzel", "güzel", "GÜZEL'İ ARA", "guzeli arar misin")) {
            assertEquals(VoiceResult.Found(guzel, VoiceAction.CALL), VoiceMatcher.match(listOf(heard), listOf(guzel, ali)))
        }
    }

    @Test
    fun aContactSavedUnderAFamilyWordIsFound() {
        val son = Contact("h", "Oğlum", "8")
        assertEquals(VoiceResult.Found(son, VoiceAction.CALL), VoiceMatcher.match(listOf("oğlumu ara"), listOf(son, mehmet)))
    }

    @Test
    fun unusualNamesSurviveMishearing() {
        val goylob = Contact("i", "Goylob", "9")
        val people = listOf(goylob, ali, aliye, mehmet)
        assertEquals(VoiceResult.Found(goylob, VoiceAction.CALL), VoiceMatcher.match(listOf("Goylob'u ara"), people))
        assertEquals(VoiceResult.Found(goylob, VoiceAction.CALL), VoiceMatcher.match(listOf("goy lob"), people))
        for (heard in listOf("koylop", "koy lop", "goylop", "göylop ara", "koylopu ara")) {
            assertEquals(heard, VoiceResult.Choose(listOf(goylob), VoiceAction.CALL), VoiceMatcher.match(listOf(heard), people))
        }
        assertEquals(VoiceResult.NotFound, VoiceMatcher.match(listOf("koy"), people))
    }

    @Test
    fun aNameWithAnEndingBeatsAJoinedName() {
        assertEquals(ali, found("Ali'ye mesaj gönder"))
        assertEquals(ali, found("Ali'yi ara"))
    }

    @Test
    fun everyLabelIsTranslatedToTurkish() {
        fun names(path: String) = Regex("<string name=\"(\\w+)\"").findAll(java.io.File(path).readText())
            .map { it.groupValues[1] }.toSet()
        assertEquals(names("src/main/res/values/strings.xml"), names("src/main/res/values-tr/strings.xml"))
    }

    @Test
    fun familyWordsMatchOnlyWhenListed() {
        assertEquals(ali, found("oğlumu ara"))
        assertEquals(ali, found("call my son"))
        assertEquals(VoiceResult.NotFound, VoiceMatcher.match(listOf("call my daughter"), all))
    }

    @Test
    fun smallMishearingsStillMatch() {
        assertEquals(
            VoiceResult.Choose(listOf(mehmet), VoiceAction.CALL),
            VoiceMatcher.match(listOf("Mehmed"), all),
        )
    }

    @Test
    fun laterGuessesCanFindSomeone() {
        assertEquals(mehmet, found("the weather", "Mehmet"))
    }

    @Test
    fun aSharedSurnameAsksInsteadOfGuessing() {
        val result = VoiceMatcher.match(listOf("Demir"), all)
        assertTrue(result is VoiceResult.Choose)
        assertEquals(setOf(ayse, mehmet), (result as VoiceResult.Choose).contacts.toSet())
    }

    @Test
    fun unrelatedSpeechFindsNobody() {
        assertEquals(VoiceResult.NotFound, VoiceMatcher.match(listOf("what time is it"), all))
        assertEquals(VoiceResult.NotFound, VoiceMatcher.match(emptyList(), all))
        assertEquals(VoiceResult.NotFound, VoiceMatcher.match(listOf("Ali"), emptyList()))
    }

    @Test
    fun anExactNameBeatsALongerOneStartingTheSame() {
        assertEquals(aliye, found("Aliye"))
    }

    @Test
    fun askingForAMessageOffersAMessage() {
        assertEquals(VoiceResult.Found(ali, VoiceAction.MESSAGE), VoiceMatcher.match(listOf("Ali'ye mesaj gönder"), all))
        assertEquals(VoiceResult.Found(ali, VoiceAction.MESSAGE), VoiceMatcher.match(listOf("oğluma MESAJ at"), all))
        assertEquals(VoiceResult.Found(mehmet, VoiceAction.MESSAGE), VoiceMatcher.match(listOf("send Mehmet a message"), all))
        val result = VoiceMatcher.match(listOf("Demir'e mesaj"), all)
        assertEquals(VoiceAction.MESSAGE, (result as VoiceResult.Choose).action)
    }

    @Test
    fun callingIsTheDefault() {
        assertEquals(VoiceResult.Found(mehmet, VoiceAction.CALL), VoiceMatcher.match(listOf("Mehmet'i ara"), all))
        assertEquals(VoiceResult.Found(mehmet, VoiceAction.CALL), VoiceMatcher.match(listOf("Mehmet", "Mehmet mesaj"), all))
    }

    @Test
    fun modelAnswersAreChecked() {
        fun parse(json: String) = VoiceAnswer.parse(json, all)
        assertEquals(
            VoiceResult.Found(ali, VoiceAction.CALL),
            parse("""{"person":"p0","sure":true,"maybe":[],"action":"call"}"""),
        )
        assertEquals(
            VoiceResult.Found(ayse, VoiceAction.MESSAGE),
            parse("""{"person":"p1","sure":true,"maybe":[],"action":"message"}"""),
        )
        assertEquals(
            VoiceResult.Choose(listOf(ali, aliye), VoiceAction.CALL),
            parse("""{"person":"p0","sure":false,"maybe":["p2","p0"],"action":"call"}"""),
        )
    }

    @Test
    fun badModelAnswersCantReachAnyone() {
        fun parse(json: String) = VoiceAnswer.parse(json, all)
        assertEquals(VoiceResult.NotFound, parse("""{"person":"none","sure":true,"maybe":[],"action":"call"}"""))
        assertEquals(VoiceResult.NotFound, parse("""{"person":"p99","sure":true,"maybe":["p50"],"action":"call"}"""))
        assertEquals(VoiceResult.NotFound, parse("""{"person":"+15551234567","sure":true,"maybe":[],"action":"call"}"""))
        assertEquals(VoiceResult.NotFound, parse("not json"))
        assertEquals(VoiceResult.NotFound, parse(""))
    }

    @Test
    fun requestsOnlyAllowPeopleOnTheList() {
        val body = VoiceRequest.forText("", listOf("oğlumu ara"), listOf(ali, mehmet))
        assertEquals(VoiceRequest.DEFAULT_MODEL, body.getString("model"))
        val properties = body.getJSONObject("response_format").getJSONObject("json_schema")
            .getJSONObject("schema").getJSONObject("properties")
        assertEquals("""["p0","p1","none"]""", properties.getJSONObject("person").getJSONArray("enum").toString())
        assertEquals("""["p0","p1"]""", properties.getJSONObject("maybe").getJSONObject("items").getJSONArray("enum").toString())
        val question = body.getJSONArray("messages").getJSONObject(1).getString("content")
        assertTrue(question.contains("oğlumu ara"))
        assertFalse(question.contains("1\n"))
    }

    @Test
    fun aChosenModelFallsBackToTheDefault() {
        val models = VoiceRequest.forText("some/model", listOf("x"), all).getJSONArray("models")
        assertEquals("some/model", models.getString(0))
        assertEquals(VoiceRequest.DEFAULT_MODEL, models.getString(1))
    }

    @Test
    fun audioIsSentAsWav() {
        val body = VoiceRequest.forAudio("", byteArrayOf(1, 2, 3), all)
        val audio = body.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
            .getJSONObject(1).getJSONObject("input_audio")
        assertEquals("wav", audio.getString("format"))
        assertEquals("AQID", audio.getString("data"))
    }

    @Test
    fun responsesAreRead() {
        assertEquals("hi", VoiceRequest.answerIn("""{"choices":[{"message":{"content":"hi"}}]}"""))
        assertEquals(null, VoiceRequest.answerIn("""{"choices":[]}"""))
        assertEquals(null, VoiceRequest.answerIn("""{"error":{"code":402}}"""))
        assertEquals("Ali'yi ara", VoiceAnswer.heardIn("""{"heard":"Ali'yi ara"}"""))
        assertEquals(null, VoiceAnswer.heardIn("nope"))
    }

    @Test
    fun recordingStopsAfterSpeechThenSilence() {
        val detector = SilenceDetector()
        repeat(5) { assertEquals(SilenceDetector.State.WAITING, detector.add(12.0, 100)) }
        repeat(3) { detector.add(300.0, 100) }
        assertEquals(SilenceDetector.State.SPEAKING, detector.add(300.0, 100))
        repeat(11) { assertEquals(SilenceDetector.State.SPEAKING, detector.add(12.0, 100)) }
        assertEquals(SilenceDetector.State.FINISHED, detector.add(12.0, 100))
    }

    @Test
    fun speechFromArmsLengthCounts() {
        val detector = SilenceDetector()
        repeat(5) { detector.add(8.0, 100) }
        detector.add(100.0, 100)
        assertEquals(SilenceDetector.State.SPEAKING, detector.add(100.0, 100))
    }

    @Test
    fun aSingleNoiseInASilentRoomIsNotSpeech() {
        val detector = SilenceDetector()
        repeat(5) { detector.add(12.0, 100) }
        assertEquals(SilenceDetector.State.WAITING, detector.add(61.0, 100))
        assertEquals(SilenceDetector.State.WAITING, detector.add(12.0, 100))
    }

    @Test
    fun recordingGivesUpIfNothingIsSaid() {
        val detector = SilenceDetector()
        var state = SilenceDetector.State.WAITING
        repeat(60) { state = detector.add(12.0, 100) }
        assertEquals(SilenceDetector.State.NO_SPEECH, state)
    }

    @Test
    fun recordingHasATimeLimit() {
        val detector = SilenceDetector()
        repeat(3) { detector.add(300.0, 100) }
        repeat(96) {
            assertEquals(SilenceDetector.State.SPEAKING, detector.add(if (it % 5 == 0) 12.0 else 300.0, 100))
        }
        assertEquals(SilenceDetector.State.FINISHED, detector.add(300.0, 100))
    }

    @Test
    fun speakingStraightAwayStillCounts() {
        val detector = SilenceDetector()
        repeat(5) { detector.add(300.0, 100) }
        repeat(11) { assertEquals(SilenceDetector.State.SPEAKING, detector.add(12.0, 100)) }
        assertEquals(SilenceDetector.State.FINISHED, detector.add(12.0, 100))
    }

    private fun pcm(vararg samples: Short): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(samples.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putShort(it) }
        return buffer.array()
    }

    @Test
    fun quietRecordingsAreTurnedUp() {
        assertTrue(pcm(3000, -3000).contentEquals(Wav.louder(pcm(100, -100))))
        assertTrue(pcm(23000, -11500).contentEquals(Wav.louder(pcm(2000, -1000))))
    }

    @Test
    fun loudOrSilentRecordingsAreLeftAlone() {
        assertTrue(pcm(30000, -30000).contentEquals(Wav.louder(pcm(30000, -30000))))
        assertTrue(pcm(0, 0).contentEquals(Wav.louder(pcm(0, 0))))
    }

    @Test
    fun wavHeaderIsCorrect() {
        val wav = Wav.wrap(ByteArray(10), 16000)
        assertEquals(54, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("data", String(wav, 36, 4))
        val buffer = java.nio.ByteBuffer.wrap(wav).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(46, buffer.getInt(4))
        assertEquals(16000, buffer.getInt(24))
        assertEquals(10, buffer.getInt(40))
    }

    @Test
    fun contactListUsesShortNumbers() {
        val list = VoiceAnswer.contactList(listOf(ali, mehmet))
        assertEquals("p0: Ali Yılmaz (also called: oğlum, my son)\np1: Mehmet Demir", list)
        assertFalse(list.contains("1\n"))
    }

    @Test
    fun whatsAppIdsBecomeNumbers() {
        assertEquals("+4712345678", ContactImport.numberFromWhatsAppId("4712345678@s.whatsapp.net"))
        assertEquals(null, ContactImport.numberFromWhatsAppId(""))
    }

    @Test
    fun peopleOnlyInWhatsAppAreMarked() {
        val rows = listOf(PhoneRow("x", "Ali", "111"), PhoneRow("y", "Veli", "+222"))
        val people = ContactImport.merge(rows, setOf("x", "y"), whatsAppOnlyIds = setOf("y")).associateBy { it.id }
        assertFalse(people.getValue("x").fromWhatsAppOnly)
        assertTrue(people.getValue("y").fromWhatsAppOnly)
    }

    @Test
    fun oneNumberIsChosenPerPerson() {
        val rows = listOf(
            PhoneRow("x", "Ali", "111"),
            PhoneRow("x", "Ali", "222", isMobile = true),
            PhoneRow("y", "Veli", "333", isMobile = true),
            PhoneRow("y", "Veli", "444", isDefaultNumber = true, starred = true),
            PhoneRow("z", "", "555"),
            PhoneRow("w", "No number", ""),
        )
        val people = ContactImport.merge(rows, setOf("x"), mapOf("y" to "Vel")).associateBy { it.id }
        assertEquals("222", people.getValue("x").phone)
        assertTrue(people.getValue("x").usesWhatsApp)
        assertEquals("444", people.getValue("y").phone)
        assertTrue(people.getValue("y").starred)
        assertEquals("Vel", people.getValue("y").nickname)
        assertEquals("555", people.getValue("z").name)
        assertFalse(people.containsKey("w"))
    }
}
