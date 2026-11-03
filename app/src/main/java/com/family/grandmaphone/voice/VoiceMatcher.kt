package com.family.grandmaphone.voice

import com.family.grandmaphone.data.Contact
import org.json.JSONException
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

enum class VoiceAction { CALL, MESSAGE }

/** Who she seems to mean. Nothing is ever dialled from this without her tapping to confirm. */
sealed interface VoiceResult {
    data class Found(val contact: Contact, val action: VoiceAction) : VoiceResult
    data class Choose(val contacts: List<Contact>, val action: VoiceAction) : VoiceResult
    data object NotFound : VoiceResult
}

/** Matching on the phone. It's instant, works offline, and sends nothing anywhere. */
object VoiceMatcher {
    private const val SURE_SCORE = 0.9
    private const val MAYBE_SCORE = 0.7
    private const val TIE_MARGIN = 0.05
    private const val LATER_GUESS_WEIGHT = 0.95
    private const val MIN_PREFIX = 3
    private const val MIN_FUZZY = 4
    private const val SOUNDS_LIKE_LIMIT = 0.88
    private const val JOINED_WEIGHT = 0.9
    private val SOUND_ALIKE = mapOf('g' to 'k', 'b' to 'p', 'd' to 't', 'v' to 'f', 'z' to 's')
    private val MESSAGE_WORDS = listOf("mesaj", "message", "sms", "text")

    fun normalize(text: String): List<String> {
        val folded = text.lowercase(Locale.ROOT).replace('ı', 'i')
        val plain = Normalizer.normalize(folded, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return plain.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    }

    fun namesOf(contact: Contact): List<String> =
        (listOf(contact.name, contact.nickname) + contact.aliases).filter { it.isNotBlank() }

    /**
     * How well a spoken word matches a name's word. A word that starts with the name still counts,
     * because many languages add endings to names ("Ali'yi", "Ayşeyi").
     */
    private fun wordScore(spoken: String, name: String): Double = when {
        spoken == name -> 1.0
        name.length >= MIN_PREFIX && spoken.startsWith(name) -> 0.92
        name.length >= MIN_PREFIX && spoken.length > name.length && spoken.startsWith(softened(name)) -> 0.92
        name.length >= MIN_FUZZY && spoken.length >= MIN_FUZZY -> {
            val soundsLike = minOf(similarity(soundOf(spoken), soundOf(name)), SOUNDS_LIKE_LIMIT)
            maxOf(similarity(spoken, name), soundsLike).takeIf { it >= 0.75 } ?: 0.0
        }
        else -> 0.0
    }

    private fun similarity(a: String, b: String): Double =
        1.0 - editDistance(a, b).toDouble() / maxOf(a.length, b.length)

    /** Letters that recognizers mix up on unfamiliar names, such as Goylob and "koylop", spelled the same. */
    private fun soundOf(word: String): String = word.map { SOUND_ALIKE[it] ?: it }.joinToString("")

    /** Turkish softens a name's last letter before an ending: Mehmet becomes "Mehmedi", Burak "Burağı". */
    private fun softened(name: String): String = when (name.last()) {
        't' -> name.dropLast(1) + "d"
        'p' -> name.dropLast(1) + "b"
        'k' -> name.dropLast(1) + "g"
        else -> name
    }

    /** The best score for any of a person's names: every word of a nickname must be spoken, or any word of their full name. */
    private fun scoreName(spoken: List<String>, name: String, isFullName: Boolean): Double {
        val words = normalize(name)
        if (words.isEmpty()) return 0.0
        // A recognizer may split a name it doesn't know into two words, such as "goy lob".
        val joined = spoken.zipWithNext { first, second -> first + second }
        val scores = words.map { word ->
            val apart = spoken.maxOfOrNull { wordScore(it, word) } ?: 0.0
            val together = joined.maxOfOrNull { wordScore(it, word) * JOINED_WEIGHT } ?: 0.0
            maxOf(apart, together)
        }
        return if (isFullName) scores.max() else scores.min()
    }

    fun score(alternatives: List<String>, contact: Contact): Double {
        var best = 0.0
        alternatives.forEachIndexed { index, alternative ->
            val spoken = normalize(alternative)
            val weight = Math.pow(LATER_GUESS_WEIGHT, index.toDouble())
            for (name in namesOf(contact)) {
                val value = scoreName(spoken, name, name == contact.name) * weight
                if (value > best) best = value
            }
        }
        return best
    }

    /** Calling is the default. The recognizer's best guess has to mention a message to change that. */
    fun actionIn(alternatives: List<String>): VoiceAction {
        val words = normalize(alternatives.firstOrNull().orEmpty())
        val asksForMessage = words.any { word -> MESSAGE_WORDS.any { word.startsWith(it) } }
        return if (asksForMessage) VoiceAction.MESSAGE else VoiceAction.CALL
    }

    fun match(alternatives: List<String>, contacts: List<Contact>): VoiceResult {
        val ranked = contacts.map { it to score(alternatives, it) }
            .filter { it.second >= MAYBE_SCORE }
            .sortedByDescending { it.second }
        val top = ranked.firstOrNull() ?: return VoiceResult.NotFound
        val second = ranked.getOrNull(1)
        val clearWinner = second == null || top.second - second.second > TIE_MARGIN
        return if (top.second >= SURE_SCORE && clearWinner) {
            VoiceResult.Found(top.first, actionIn(alternatives))
        } else {
            VoiceResult.Choose(ranked.take(4).map { it.first }, actionIn(alternatives))
        }
    }

    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }
}

/** Building the question for the AI model and checking its answer. */
object VoiceAnswer {
    /** Contacts are sent under short numbers, so the answer can only ever point at a real person. */
    fun contactList(contacts: List<Contact>): String =
        contacts.withIndex().joinToString("\n") { (index, contact) ->
            val other = (VoiceMatcher.namesOf(contact) - contact.name).joinToString(", ")
            "p$index: ${contact.name}" + if (other.isEmpty()) "" else " (also called: $other)"
        }

    /** What the model heard, if it said. */
    fun heardIn(answer: String): String? = try {
        JSONObject(answer).optString("heard").ifBlank { null }
    } catch (_: JSONException) {
        null
    }

    fun parse(answer: String, contacts: List<Contact>): VoiceResult {
        val json = try {
            JSONObject(answer)
        } catch (_: JSONException) {
            return VoiceResult.NotFound
        }
        fun person(id: String): Contact? =
            id.removePrefix("p").toIntOrNull()?.let { contacts.getOrNull(it) }
        val action = if (json.optString("action") == "message") VoiceAction.MESSAGE else VoiceAction.CALL
        val best = person(json.optString("person"))
        val maybe = json.optJSONArray("maybe")?.let { list ->
            (0 until list.length()).mapNotNull { person(list.optString(it)) }
        }.orEmpty()
        val everyone = (listOfNotNull(best) + maybe).distinct()
        return when {
            best != null && json.optBoolean("sure") -> VoiceResult.Found(best, action)
            everyone.isNotEmpty() -> VoiceResult.Choose(everyone.take(4), action)
            else -> VoiceResult.NotFound
        }
    }
}
