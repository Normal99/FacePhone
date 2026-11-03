package com.family.grandmaphone.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** All app state, kept as one small JSON file in private storage. */
object Repo {
    val contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contactsWithoutNumber = MutableStateFlow<List<ContactWithoutNumber>>(emptyList())
    val inbox = MutableStateFlow<List<InboxItem>>(emptyList())
    val missedCalls = MutableStateFlow<Set<String>>(emptySet())
    val whatsAppUnread = MutableStateFlow<Set<String>>(emptySet())
    val settings = MutableStateFlow(Settings())

    /** Bumped when a contact's files change, so screens reload photos. */
    val filesVersion = MutableStateFlow(0)

    private lateinit var root: File
    private val stateFile get() = File(root, "state.json")
    private val seenMms = LinkedHashSet<String>()

    @Synchronized
    fun init(context: Context) {
        root = context.filesDir
        if (!stateFile.exists()) return
        val json = JSONObject(stateFile.readText())
        contacts.value = json.getJSONArray("contacts").map { obj ->
            Contact(
                obj.getString("id"),
                obj.getString("name"),
                obj.getString("phone"),
                obj.optBoolean("whatsapp"),
                obj.optBoolean("help"),
                obj.optBoolean("starred"),
                obj.optBoolean("hidden"),
                obj.optString("nickname"),
                obj.optJSONArray("aliases")?.strings().orEmpty(),
                obj.optBoolean("whatsappOnly"),
            )
        }
        inbox.value = json.getJSONArray("inbox").map { obj ->
            InboxItem(
                obj.getString("id"),
                obj.optString("contact").ifEmpty { null },
                obj.getString("address"),
                MessageKind.valueOf(obj.getString("kind")),
                obj.optString("file").ifEmpty { null },
                obj.optString("text").ifEmpty { null },
                obj.getLong("time"),
                obj.optBoolean("seen"),
            )
        }
        missedCalls.value = json.getJSONArray("missed").strings().toSet()
        seenMms.addAll(json.getJSONArray("seenMms").strings())
        val s = json.getJSONObject("settings")
        settings.value = Settings(
            s.optString("countryCode"),
            s.optString("speechLanguage"),
            s.optString("emergencyNumber"),
            s.optString("familyNumber"),
            s.optString("helpMessage"),
            s.optString("chargePrompt"),
            s.optBoolean("favouritesOnly"),
            s.optString("voiceApiKey"),
            s.optString("voiceModel"),
            s.optBoolean("voiceHearsAudio"),
        )
    }

    @Synchronized
    private fun save() {
        val json = JSONObject()
        json.put("contacts", JSONArray(contacts.value.map {
            JSONObject()
                .put("id", it.id).put("name", it.name).put("phone", it.phone)
                .put("whatsapp", it.usesWhatsApp).put("help", it.helpsInEmergency)
                .put("starred", it.starred).put("hidden", it.hidden).put("nickname", it.nickname)
                .put("aliases", JSONArray(it.aliases)).put("whatsappOnly", it.fromWhatsAppOnly)
        }))
        json.put("inbox", JSONArray(inbox.value.map {
            JSONObject()
                .put("id", it.id).put("contact", it.contactId ?: "").put("address", it.address)
                .put("kind", it.kind.name).put("file", it.fileName ?: "").put("text", it.text ?: "")
                .put("time", it.time).put("seen", it.seen)
        }))
        json.put("missed", JSONArray(missedCalls.value.toList()))
        json.put("seenMms", JSONArray(seenMms.toList()))
        val s = settings.value
        json.put("settings", JSONObject()
            .put("countryCode", s.countryCode).put("speechLanguage", s.speechLanguage)
            .put("emergencyNumber", s.emergencyNumber).put("familyNumber", s.familyNumber)
            .put("helpMessage", s.helpMessage).put("chargePrompt", s.chargePrompt)
            .put("favouritesOnly", s.favouritesOnly).put("voiceApiKey", s.voiceApiKey)
            .put("voiceModel", s.voiceModel).put("voiceHearsAudio", s.voiceHearsAudio))
        val temp = File(root, "state.json.tmp")
        temp.writeText(json.toString())
        temp.renameTo(stateFile)
    }

    // ---- Contacts ----

    private fun contactDir(id: String) = File(root, "contacts/$id").apply { mkdirs() }
    fun photoFile(id: String) = File(contactDir(id), "photo.jpg")
    fun nameClipFile(id: String) = File(contactDir(id), "name.m4a")
    fun ringClipFile(id: String) = File(contactDir(id), "ring.m4a")
    fun whatsAppPhotoFile(id: String) = File(contactDir(id), "whatsapp.png")
    fun chargeClipFile() = File(root, "charge.m4a")
    fun inboxFile(name: String) = File(File(root, "inbox").apply { mkdirs() }, name)

    fun contact(id: String?) = contacts.value.firstOrNull { it.id == id }

    /** Faces to show her: favourites first, then by name. */
    fun visibleContacts(all: List<Contact>, settings: Settings): List<Contact> =
        all.filterNot { it.hidden }
            .filter { !settings.favouritesOnly || it.starred }
            .sortedWith(compareBy<Contact> { !it.starred }.thenBy { it.name.lowercase() })

    /** Updates the family's own settings for a person. */
    @Synchronized
    fun saveContact(contact: Contact) {
        contacts.value = contacts.value.map { if (it.id == contact.id) contact else it }
        save()
    }

    /** Replaces the people with the phone's contacts list, keeping the family's settings for each. */
    @Synchronized
    fun applyImported(imported: List<ImportedContact>) {
        val existing = contacts.value.associateBy { it.id }
        val updated = imported.map { person ->
            val old = existing[person.id]
            Contact(
                person.id, person.name, person.phone, person.usesWhatsApp,
                old?.helpsInEmergency ?: false, person.starred, old?.hidden ?: false,
                person.nickname, old?.aliases.orEmpty(), person.fromWhatsAppOnly,
            )
        }
        val ids = updated.map { it.id }.toSet()
        val gone = existing.keys - ids
        if (updated == contacts.value) return
        contacts.value = updated
        gone.forEach { contactDir(it).deleteRecursively() }
        missedCalls.value = missedCalls.value - gone
        // Messages match whoever now has that number, and messages from removed people become unknown.
        inbox.value = inbox.value.map { item ->
            item.copy(contactId = ContactMatch.byNumber(updated, item.address)?.id)
        }
        save()
    }

    fun touchFiles() {
        filesVersion.value = filesVersion.value + 1
    }

    /** Keeps a WhatsApp picture, used when the person has no photo in the phone's contacts. */
    @Synchronized
    fun saveWhatsAppPhoto(id: String, png: ByteArray) {
        val file = whatsAppPhotoFile(id)
        if (file.exists() && file.readBytes().contentEquals(png)) return
        file.writeBytes(png)
        touchFiles()
    }

    // ---- Messages ----

    @Synchronized
    fun addMessage(item: InboxItem) {
        inbox.value = inbox.value + item
        save()
    }

    @Synchronized
    fun markSeen(itemId: String) {
        inbox.value = inbox.value.map { if (it.id == itemId) it.copy(seen = true) else it }
        save()
    }

    /** True the first time an MMS is announced. Carriers can announce the same one twice. */
    @Synchronized
    fun firstSightOfMms(transactionId: String): Boolean {
        if (!seenMms.add(transactionId)) return false
        while (seenMms.size > 200) seenMms.remove(seenMms.first())
        save()
        return true
    }

    // ---- Missed calls and settings ----

    @Synchronized
    fun addMissedCall(contactId: String) {
        missedCalls.value = missedCalls.value + contactId
        save()
    }

    @Synchronized
    fun clearMissedCall(contactId: String) {
        if (contactId !in missedCalls.value) return
        missedCalls.value = missedCalls.value - contactId
        save()
    }

    @Synchronized
    fun saveSettings(value: Settings) {
        settings.value = value
        save()
    }

    private fun <T> JSONArray.map(transform: (JSONObject) -> T): List<T> =
        (0 until length()).map { transform(getJSONObject(it)) }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
}
