package com.vezzo.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import android.telephony.SmsManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.io.File
import java.text.Normalizer
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/* ===================================================================
   Section issue de Data.kt
   =================================================================== */

/** Un contact de la liste des "contacts fréquents". */
data class Contact(val name: String, val phone: String) {
    /** Prénom seul, utilisé pour personnaliser le SMS et pour l'export anonymisé. */
    val firstName: String
        get() = name.trim().split(" ").firstOrNull().orEmpty().ifBlank { name }
}

object Defaults {
    const val ADMIN_EMAIL = "romain.bilquez@gmail.com"

    const val SMS_INITIAL =
        "Bonjour {PRENOM}, peux-tu m'indiquer tes disponibilités pour {MOIS} ? " +
        "Indique-moi les jours où tu es libre, en précisant si c'est le matin, " +
        "l'après-midi ou la journée complète. Merci !"

    const val SMS_RELANCE =
        "Bonjour {PRENOM}, petit rappel : je n'ai pas encore reçu tes disponibilités pour {MOIS}. " +
        "Dis-moi les jours où tu es libre, et si c'est le matin, l'après-midi ou " +
        "la journée entière. Merci beaucoup !"
}

/** Mois à planifier : par défaut le mois suivant le mois en cours. */
object MonthInfo {
    fun target(): YearMonth = YearMonth.now().plusMonths(1)

    fun label(ym: YearMonth = target()): String {
        val name = ym.month.getDisplayName(TextStyle.FULL, Locale.FRENCH)
        return "$name ${ym.year}"
    }

    fun dayCount(ym: YearMonth = target()): Int = ym.lengthOfMonth()
}

/**
 * Stockage local simple : SharedPreferences + JSON.
 * Aucune donnée ne quitte le téléphone tant que tu ne lances pas un export.
 */
class Store(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("vezzo_store", Context.MODE_PRIVATE)

    var contacts: List<Contact>
        get() {
            val raw = prefs.getString(KEY_CONTACTS, null) ?: return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    Contact(o.getString("name"), o.getString("phone"))
                }
            }.getOrDefault(emptyList())
        }
        set(value) {
            val arr = JSONArray()
            value.forEach { c ->
                arr.put(JSONObject().put("name", c.name).put("phone", c.phone))
            }
            prefs.edit().putString(KEY_CONTACTS, arr.toString()).apply()
        }

    var smsInitial: String
        get() = prefs.getString(KEY_SMS_INITIAL, Defaults.SMS_INITIAL) ?: Defaults.SMS_INITIAL
        set(v) = prefs.edit().putString(KEY_SMS_INITIAL, v).apply()

    var smsRelance: String
        get() = prefs.getString(KEY_SMS_RELANCE, Defaults.SMS_RELANCE) ?: Defaults.SMS_RELANCE
        set(v) = prefs.edit().putString(KEY_SMS_RELANCE, v).apply()

    var adminEmail: String
        get() = prefs.getString(KEY_ADMIN_EMAIL, Defaults.ADMIN_EMAIL) ?: Defaults.ADMIN_EMAIL
        set(v) = prefs.edit().putString(KEY_ADMIN_EMAIL, v).apply()

    /** Date du dernier envoi groupé : sert de point de départ pour lire les réponses. */
    var lastSendMillis: Long
        get() = prefs.getLong(KEY_LAST_SEND, 0L)
        set(v) = prefs.edit()
            .putLong(KEY_LAST_SEND, v)
            .putStringSet(KEY_MANUAL_ANSWERED, emptySet())
            .apply()

    /** Numéros normalisés que l'utilisateur a validés manuellement comme "a répondu". */
    var manuallyAnswered: Set<String>
        get() = prefs.getStringSet(KEY_MANUAL_ANSWERED, emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet(KEY_MANUAL_ANSWERED, v).apply()

    fun markAnswered(phone: String) {
        manuallyAnswered = manuallyAnswered + PhoneUtils.normalize(phone)
    }

    /** Si activé, l'export ne contient que les prénoms. */
    var anonymize: Boolean
        get() = prefs.getBoolean(KEY_ANONYMIZE, false)
        set(v) = prefs.edit().putBoolean(KEY_ANONYMIZE, v).apply()

    fun addContact(c: Contact) {
        val normalized = PhoneUtils.normalize(c.phone)
        if (contacts.any { PhoneUtils.normalize(it.phone) == normalized }) return
        contacts = contacts + c
    }

    fun removeContact(c: Contact) {
        contacts = contacts.filterNot {
            it.name == c.name && PhoneUtils.normalize(it.phone) == PhoneUtils.normalize(c.phone)
        }
    }

    private companion object {
        const val KEY_CONTACTS = "contacts"
        const val KEY_SMS_INITIAL = "sms_initial"
        const val KEY_SMS_RELANCE = "sms_relance"
        const val KEY_ADMIN_EMAIL = "admin_email"
        const val KEY_LAST_SEND = "last_send"
        const val KEY_ANONYMIZE = "anonymize"
        const val KEY_MANUAL_ANSWERED = "manual_answered"
    }
}

object PhoneUtils {
    /**
     * Réduit un numéro à ses 9 derniers chiffres.
     * Cela permet de faire correspondre 06 12 34 56 78 et +33 6 12 34 56 78.
     */
    fun normalize(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return if (digits.length > 9) digits.takeLast(9) else digits
    }

    fun same(a: String, b: String): Boolean {
        val na = normalize(a)
        val nb = normalize(b)
        return na.isNotEmpty() && na == nb
    }
}

/** Remplace les variables {PRENOM} et {MOIS} dans un modèle de SMS. */
fun String.fillTemplate(contact: Contact): String =
    replace("{PRENOM}", contact.firstName)
        .replace("{MOIS}", MonthInfo.label())

/* ===================================================================
   Section issue de Sms.kt
   =================================================================== */

/** Un SMS reçu d'un contact. */
data class Reply(val dateMillis: Long, val body: String)

/** Résultat d'un envoi groupé. */
data class SendResult(val sent: Int, val failed: List<String>)

/** État d'un contact vis-à-vis de la demande de disponibilités. */
enum class ReplyStatus {
    /** A répondu, et le message ressemble bien à une réponse de disponibilités. */
    ANSWERED,

    /** A envoyé un ou plusieurs SMS, mais aucun ne ressemble à une réponse. */
    UNCLEAR,

    /** N'a rien envoyé depuis l'envoi groupé. */
    NONE
}

data class ContactStatus(
    val contact: Contact,
    val status: ReplyStatus,
    val replies: List<Reply>
) {
    val lastMessage: String
        get() = replies.lastOrNull()?.body?.trim().orEmpty()
}

/**
 * Décide si un SMS reçu constitue une réponse à la demande de disponibilités.
 * Volontairement large : mieux vaut classer en "à vérifier" que d'ignorer une vraie réponse.
 */
object ReplyClassifier {

    /** Mots entiers uniquement : "jour" ne doit pas être trouvé dans "bonjour". */
    private val KEYWORDS = listOf(
        "dispo", "dispos", "disponible", "disponibles", "disponibilite", "disponibilites",
        "indispo", "indisponible", "indisponibles",
        "matin", "matins", "matinee",
        "aprem", "aprems", "apres-midi", "apresmidi", "pm", "am", "midi",
        "jour", "jours", "journee", "journees", "toute", "complet", "complete",
        "lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche",
        "lundis", "mardis", "mercredis", "jeudis", "vendredis", "samedis", "dimanches",
        "semaine", "semaines", "weekend", "week-end",
        "libre", "libres", "occupe", "occupee", "pris", "prise",
        "present", "presente", "absent", "absente",
        "conge", "conges", "vacances", "repos", "travail", "boulot",
        "janvier", "fevrier", "mars", "avril", "mai", "juin", "juillet",
        "aout", "septembre", "octobre", "novembre", "decembre",
        "rien", "aucun", "aucune", "peux", "peut", "pourrai", "possible"
    )

    private val KEYWORD_REGEX = Regex(
        "\\b(" + KEYWORDS.joinToString("|") { Regex.escape(it) } + ")\\b"
    )

    /** Plages type "du 8 au 12". */
    private val RANGE_REGEX = Regex("\\bdu\\s+[0-9]{1,2}\\s+au\\s+[0-9]{1,2}\\b")

    private val NUMBER_REGEX = Regex("\\b([0-9]{1,2})\\b")

    /** Minuscules sans accents, pour comparer de façon fiable. */
    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")

    fun looksLikeAvailability(body: String): Boolean {
        val t = normalize(body)
        if (t.isBlank()) return false

        if (KEYWORD_REGEX.containsMatchIn(t)) return true
        if (RANGE_REGEX.containsMatchIn(t)) return true

        // Message court composé de plusieurs nombres compris entre 1 et 31 :
        // typiquement une liste de jours envoyée sans aucun mot.
        val dayNumbers = NUMBER_REGEX.findAll(t)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .count { it in 1..31 }
        if (dayNumbers >= 2 && t.length <= 200) return true

        return false
    }
}

object SmsService {

    private fun manager(context: Context): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

    private fun sendOne(context: Context, phone: String, body: String) {
        val sms = manager(context)
        val parts = sms.divideMessage(body)
        if (parts.size <= 1) {
            sms.sendTextMessage(phone, null, body, null, null)
        } else {
            sms.sendMultipartTextMessage(phone, null, parts, null, null)
        }
    }

    /**
     * Envoie le même message à toute une liste, en personnalisant {PRENOM} et {MOIS}.
     * Une courte pause sépare chaque envoi pour éviter un blocage opérateur.
     */
    fun sendToAll(context: Context, contacts: List<Contact>, template: String): SendResult {
        var sent = 0
        val failed = mutableListOf<String>()
        contacts.forEach { contact ->
            try {
                sendOne(context, contact.phone, template.fillTemplate(contact))
                sent++
                Thread.sleep(400)
            } catch (e: Exception) {
                failed.add(contact.name)
            }
        }
        return SendResult(sent, failed)
    }

    /**
     * Lit les SMS reçus depuis [sinceMillis] et les associe aux contacts connus.
     *
     * Si aucun envoi groupé n'a encore eu lieu, [sinceMillis] vaut zéro : on renvoie
     * des listes vides plutôt que l'intégralité de la boîte de réception.
     */
    fun readReplies(
        context: Context,
        contacts: List<Contact>,
        sinceMillis: Long
    ): Map<String, List<Reply>> {
        val byContact = linkedMapOf<String, MutableList<Reply>>()
        contacts.forEach { byContact[PhoneUtils.normalize(it.phone)] = mutableListOf() }

        if (contacts.isEmpty() || sinceMillis <= 0L) return byContact

        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE
        )

        try {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                projection,
                "${Telephony.Sms.DATE} > ?",
                arrayOf(sinceMillis.toString()),
                "${Telephony.Sms.DATE} ASC"
            )?.use { cursor ->
                val iAddr = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val iBody = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val iDate = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                while (cursor.moveToNext()) {
                    val address = cursor.getString(iAddr) ?: continue
                    val bucket = byContact[PhoneUtils.normalize(address)] ?: continue
                    bucket.add(Reply(cursor.getLong(iDate), cursor.getString(iBody).orEmpty()))
                }
            }
        } catch (e: Exception) {
            // Permission refusée ou fournisseur indisponible : listes vides.
        }

        return byContact
    }

    /** Une ligne brute de la boîte de réception, telle que le système la renvoie. */
    data class InboxRow(
        val address: String,
        val normalized: String,
        val dateMillis: Long,
        val preview: String,
        val matchedContact: String?
    )

    data class InboxDump(
        val error: String?,
        val totalInInbox: Int,
        val rows: List<InboxRow>
    )

    /**
     * Lecture brute utilisée par l'écran de diagnostic. Contrairement à readReplies,
     * elle ne filtre sur aucune date et remonte explicitement les erreurs.
     */
    fun rawInbox(context: Context, contacts: List<Contact>, limit: Int = 25): InboxDump {
        val rows = mutableListOf<InboxRow>()
        var total = 0
        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE
        )
        return try {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                projection,
                null,
                null,
                "${Telephony.Sms.DATE} DESC"
            )?.use { cursor ->
                total = cursor.count
                val iAddr = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val iBody = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val iDate = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                while (cursor.moveToNext() && rows.size < limit) {
                    val addr = cursor.getString(iAddr).orEmpty()
                    val norm = PhoneUtils.normalize(addr)
                    val match = contacts.firstOrNull {
                        PhoneUtils.normalize(it.phone) == norm
                    }?.name
                    rows.add(
                        InboxRow(
                            address = addr,
                            normalized = norm,
                            dateMillis = cursor.getLong(iDate),
                            preview = cursor.getString(iBody).orEmpty().take(60),
                            matchedContact = match
                        )
                    )
                }
            }
            InboxDump(null, total, rows)
        } catch (e: Exception) {
            InboxDump(e.javaClass.simpleName + " : " + (e.message ?: "sans détail"), total, rows)
        }
    }

    /**
     * Classe chaque contact selon qu'il a répondu, envoyé un message non reconnu,
     * ou rien envoyé du tout. [manuallyAnswered] contient les numéros normalisés
     * que l'utilisateur a validés lui-même.
     */
    fun statuses(
        context: Context,
        contacts: List<Contact>,
        sinceMillis: Long,
        manuallyAnswered: Set<String> = emptySet()
    ): List<ContactStatus> {
        val replies = readReplies(context, contacts, sinceMillis)
        return contacts.map { contact ->
            val key = PhoneUtils.normalize(contact.phone)
            val list = replies[key].orEmpty()
            val status = when {
                // Validation manuelle : couvre le cas d'une réponse donnée de vive voix,
                // sans aucun SMS reçu.
                key in manuallyAnswered -> ReplyStatus.ANSWERED
                list.isEmpty() -> ReplyStatus.NONE
                list.any { ReplyClassifier.looksLikeAvailability(it.body) } -> ReplyStatus.ANSWERED
                else -> ReplyStatus.UNCLEAR
            }
            ContactStatus(contact, status, list)
        }
    }
}

/* ===================================================================
   Section issue de Export.kt
   =================================================================== */

object ExportBuilder {

    private val stamp: DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd/MM 'à' HH:mm", Locale.FRENCH)

    private val fileStamp: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm", Locale.FRENCH)

    /**
     * Construit le fichier texte destiné à être collé dans une IA.
     * Il contient la consigne de sortie attendue, puis les SMS bruts groupés par personne.
     */
    /**
     * Début de la fenêtre de collecte pour l'export : le premier jour du mois en cours,
     * ou la date du dernier envoi groupé si celui-ci est antérieur. On récupère ainsi
     * l'intégralité des échanges du mois, sans filtrage sur le contenu.
     */
    private fun exportWindowStart(store: Store): Long {
        val monthStart = YearMonth.now()
            .atDay(1)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val lastSend = store.lastSendMillis
        return if (lastSend > 0L && lastSend < monthStart) lastSend else monthStart
    }

    fun build(context: Context, store: Store): String {
        val contacts = store.contacts
        val since = exportWindowStart(store)
        val statuses = SmsService.statuses(context, contacts, since, store.manuallyAnswered)
        val month = MonthInfo.label()
        val days = MonthInfo.dayCount()
        val answered = statuses.count { it.status == ReplyStatus.ANSWERED }
        val zone = ZoneId.systemDefault()

        val sb = StringBuilder()

        sb.appendLine("=== EXPORT VEZZO — DISPONIBILITÉS ===")
        sb.appendLine("Mois à planifier : $month ($days jours)")
        sb.appendLine("Date de l'export : " + stamp.format(Instant.now().atZone(zone)))
        sb.appendLine("Personnes contactées : ${contacts.size}")
        sb.appendLine("Réponses identifiées à ce jour : $answered")
        sb.appendLine(
            "Messages collectés depuis le : " +
                stamp.format(Instant.ofEpochMilli(since).atZone(zone))
        )
        sb.appendLine()
        sb.appendLine("Ce fichier contient TOUS les messages reçus de ces personnes sur la")
        sb.appendLine("période, sans tri préalable. Certains n'ont aucun rapport avec les")
        sb.appendLine("disponibilités : c'est à toi de faire le tri.")
        if (store.lastSendMillis <= 0L) {
            sb.appendLine()
            sb.appendLine("ATTENTION : aucun envoi groupé n'a encore été effectué.")
        }
        sb.appendLine()

        sb.appendLine("--- CE QUE JE TE DEMANDE ---")
        sb.appendLine()
        sb.appendLine("1) LE PLANNING DU MOIS")
        sb.appendLine("Construis un tableau avec une ligne par jour du mois, du 1 au $days,")
        sb.appendLine("et trois colonnes : MATIN | APRÈS-MIDI | JOUR COMPLET.")
        sb.appendLine("Dans chaque case, écris le nom des personnes disponibles sur ce créneau.")
        sb.appendLine("Une personne qui annonce une journée complète doit apparaître UNIQUEMENT")
        sb.appendLine("dans la colonne JOUR COMPLET, et pas dans MATIN ni APRÈS-MIDI.")
        sb.appendLine("Laisse la case vide si personne n'est disponible.")
        sb.appendLine()
        sb.appendLine("2) UNE SYNTHÈSE EN UNE PHRASE")
        sb.appendLine("Dis-moi en une seule phrase si le planning du mois est complet,")
        sb.appendLine("c'est-à-dire si chaque jour est couvert par au moins une personne.")
        sb.appendLine("Si ce n'est pas le cas, liste les jours et créneaux sans personne.")
        sb.appendLine()
        sb.appendLine("3) LA VERSION HTML")
        sb.appendLine("Donne-moi ensuite le même tableau sous forme d'un fichier HTML autonome,")
        sb.appendLine("dans un bloc de code unique, prêt à être enregistré en .html et ouvert")
        sb.appendLine("dans un navigateur. Il doit contenir le titre du mois, la phrase de synthèse,")
        sb.appendLine("puis le tableau complet, avec un style CSS intégré et lisible sur téléphone.")
        sb.appendLine("Les jours sans aucune personne disponible doivent être visuellement signalés.")
        sb.appendLine()

        sb.appendLine("--- RÈGLES D'INTERPRÉTATION DES RÉPONSES ---")
        sb.appendLine("Les gens répondent librement, avec leurs propres mots, sans aucun format")
        sb.appendLine("imposé. Les messages sont souvent approximatifs. Interprète au mieux :")
        sb.appendLine("- \"dispo le 3 et le 4\" sans précision de créneau = journée complète.")
        sb.appendLine("- \"le 5 au matin\", \"le 5 dans la matinée\" = matin du 5.")
        sb.appendLine("- \"l'aprem\", \"l'après-midi\", \"en PM\" = après-midi.")
        sb.appendLine("- \"toute la journée\", \"en entier\", \"toute la journée du 9\" = journée complète.")
        sb.appendLine("- Une abréviation isolée (M, A, J après un chiffre) doit être comprise")
        sb.appendLine("  comme matin, après-midi ou journée, mais ce format n'est pas demandé.")
        sb.appendLine("- \"du 8 au 12\" = tous les jours de 8 à 12 inclus.")
        sb.appendLine("- \"tous les lundis\" = tous les lundis du mois concerné.")
        sb.appendLine("- Les chiffres désignent des jours du mois de $month, jamais un autre mois.")
        sb.appendLine("- Ignore les messages hors sujet (publicité, opérateur, conversation autre).")
        sb.appendLine("- Si une réponse est ambiguë ou incompréhensible, ne devine pas :")
        sb.appendLine("  signale-la dans une courte liste \"À VÉRIFIER\" à la fin, avec le nom.")
        sb.appendLine("- Si une personne envoie plusieurs SMS, prends en compte l'ensemble,")
        sb.appendLine("  et en cas de contradiction, retiens le message le plus récent.")
        sb.appendLine()

        sb.appendLine("--- RÉPONSES REÇUES ---")
        sb.appendLine()

        if (contacts.isEmpty()) {
            sb.appendLine("(aucun contact enregistré)")
        }

        statuses.forEach { cs ->
            val displayName = if (store.anonymize) cs.contact.firstName else cs.contact.name
            sb.appendLine("### $displayName")
            if (cs.replies.isEmpty()) {
                sb.appendLine("(aucune réponse reçue)")
            } else {
                cs.replies.forEach { r ->
                    val date = stamp.format(Instant.ofEpochMilli(r.dateMillis).atZone(zone))
                    sb.appendLine("[$date] ${r.body.trim()}")
                }
            }
            sb.appendLine()
        }

        sb.appendLine("--- FIN DE L'EXPORT ---")
        return sb.toString()
    }

    fun fileName(): String =
        "vezzo_" + fileStamp.format(Instant.now().atZone(ZoneId.systemDefault())) + ".txt"

    /** Écrit l'export dans le cache et renvoie le fichier, partageable via FileProvider. */
    fun writeToCache(context: Context, content: String): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, fileName())
        file.writeText(content, Charsets.UTF_8)
        return file
    }

    fun copyToClipboard(context: Context, content: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Export Vezzo", content))
    }

    /** Ouvre le sélecteur Android pour envoyer l'export vers l'app de son choix. */
    fun share(context: Context, content: String) {
        val file = writeToCache(context, content)
        val uri = FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Disponibilités ${MonthInfo.label()}")
            putExtra(Intent.EXTRA_TEXT, content)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Envoyer l'export"))
    }

    /**
     * Envoie l'export par mail à l'administrateur, destinataire déjà rempli.
     * Le contenu est mis à la fois dans le corps du mail et en pièce jointe.
     */
    fun sendToAdmin(context: Context, store: Store, content: String) {
        val file = writeToCache(context, content)
        val uri = FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(store.adminEmail))
            putExtra(Intent.EXTRA_SUBJECT, "Vezzo — disponibilités ${MonthInfo.label()}")
            putExtra(Intent.EXTRA_TEXT, content)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Envoyer à l'administrateur"))
    }
}

/* ===================================================================
   Section issue de MainActivity.kt
   =================================================================== */

enum class Screen { HOME, SMS_GROUPE, RELANCE, EXPORT, DIAGNOSTIC }

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                RootScreen()
            }
        }
    }
}

val REQUIRED_PERMISSIONS = arrayOf(
    Manifest.permission.SEND_SMS,
    Manifest.permission.READ_SMS,
    Manifest.permission.RECEIVE_SMS
)

fun hasAllPermissions(context: android.content.Context): Boolean =
    REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

/**
 * Filtre d'entrée : explique pourquoi les autorisations sont nécessaires,
 * les demande, et propose une porte de sortie vers les paramètres système
 * si Android a définitivement bloqué la demande.
 */
@Composable
fun RootScreen() {
    val context = LocalContext.current
    val activity = context as Activity
    var granted by remember { mutableStateOf(hasAllPermissions(context)) }
    var asked by remember { mutableStateOf(false) }
    var bypass by remember { mutableStateOf(false) }

    // Re-vérifie au retour depuis les paramètres système.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = hasAllPermissions(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        asked = true
        granted = result.values.all { it }
    }

    val permanentlyBlocked = asked && !granted &&
        REQUIRED_PERMISSIONS.none { activity.shouldShowRequestPermissionRationale(it) }

    if (granted || bypass) {
        VezzoApp()
    } else {
        PermissionScreen(
            blocked = permanentlyBlocked,
            onRequest = { launcher.launch(REQUIRED_PERMISSIONS) },
            onOpenSettings = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
            },
            onContinueAnyway = { bypass = true }
        )
    }
}

@Composable
fun PermissionScreen(
    blocked: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onContinueAnyway: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Bienvenue dans Vezzo", fontWeight = FontWeight.Bold, fontSize = 24.sp)
        Spacer(Modifier.height(18.dp))
        Text(
            "Pour fonctionner, l'application a besoin de deux autorisations :",
            fontSize = 15.sp
        )
        Spacer(Modifier.height(14.dp))
        Text("• Envoyer des SMS", fontWeight = FontWeight.Medium)
        Text(
            "pour transmettre la demande de disponibilités à toute ta liste en un appui.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Text("• Lire les SMS reçus", fontWeight = FontWeight.Medium)
        Text(
            "pour repérer qui a répondu et rassembler les réponses dans l'export.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(18.dp))
        Text(
            "Rien n'est envoyé sur Internet. Tes messages et tes contacts restent " +
                "sur ce téléphone, sauf si tu déclenches toi-même un export.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(28.dp))

        if (blocked) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Autorisation bloquée par Android", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Android bloque les permissions SMS pour les applications installées " +
                            "hors Play Store. Ouvre les paramètres, puis : menu ⋮ en haut à " +
                            "droite → Autoriser les paramètres restreints. Reviens ensuite " +
                            "dans Autorisations → SMS → Autoriser.",
                        fontSize = 13.sp
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("OUVRIR LES PARAMÈTRES", fontWeight = FontWeight.Bold) }
        } else {
            Button(
                onClick = onRequest,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("AUTORISER", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Autoriser manuellement dans les paramètres") }
        }

        Spacer(Modifier.height(4.dp))
        TextButton(
            onClick = onContinueAnyway,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Continuer sans autoriser (fonctions limitées)") }
    }
}

@Composable
fun VezzoApp() {
    val context = LocalContext.current
    val store = remember { Store(context) }
    var screen by remember { mutableStateOf(Screen.HOME) }
    var refresh by remember { mutableStateOf(0) }

    when (screen) {
        Screen.HOME -> HomeScreen(store) { screen = it }
        Screen.SMS_GROUPE -> SmsGroupeScreen(store, refresh, { refresh++ }) { screen = Screen.HOME }
        Screen.RELANCE -> RelanceScreen(store) { screen = Screen.HOME }
        Screen.EXPORT -> ExportScreen(store) { screen = Screen.HOME }
        Screen.DIAGNOSTIC -> DiagnosticScreen(store) { screen = Screen.HOME }
    }
}

/* ---------------------------------------------------------------- ÉCRAN D'ACCUEIL */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(store: Store, go: (Screen) -> Unit) {
    val context = LocalContext.current
    var showEmailDialog by remember { mutableStateOf(false) }
    var anonymize by remember { mutableStateOf(store.anonymize) }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("Vezzo", fontWeight = FontWeight.Bold)
                    Text(
                        "Planning ${MonthInfo.label()}",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(20.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            BigButton("SMS groupé", "Envoyer la demande de disponibilités") {
                go(Screen.SMS_GROUPE)
            }
            BigButton("Relance", "Qui n'a pas encore répondu ?") {
                go(Screen.RELANCE)
            }
            BigButton("Export pour l'IA", "Récupérer tous les SMS du mois") {
                go(Screen.EXPORT)
            }
            BigButton(
                "Envoyer cette synthèse à l'administrateur",
                store.adminEmail,
                primary = true
            ) {
                val content = ExportBuilder.build(context, store)
                runCatching { ExportBuilder.sendToAdmin(context, store, content) }
                    .onFailure {
                        Toast.makeText(
                            context,
                            "Aucune application mail trouvée sur le téléphone.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
            }

            Spacer(Modifier.height(6.dp))
            HorizontalDivider()

            TextButton(onClick = { go(Screen.DIAGNOSTIC) }) {
                Text("Diagnostic de lecture des SMS")
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Prénoms seulement", fontWeight = FontWeight.Medium)
                    Text(
                        "Masque les noms de famille dans l'export",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = anonymize, onCheckedChange = {
                    anonymize = it
                    store.anonymize = it
                })
            }

            TextButton(onClick = { showEmailDialog = true }) {
                Text("Modifier l'adresse de l'administrateur")
            }

            Text(
                "${store.contacts.size} contact(s) enregistré(s)",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showEmailDialog) {
        TextEditDialog(
            title = "Adresse de l'administrateur",
            initial = store.adminEmail,
            singleLine = true,
            onDismiss = { showEmailDialog = false },
            onSave = { store.adminEmail = it.trim(); showEmailDialog = false }
        )
    }
}

/* ------------------------------------------------------------------- SMS GROUPÉ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsGroupeScreen(store: Store, refreshKey: Int, onRefresh: () -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    var contacts by remember(refreshKey) { mutableStateOf(store.contacts) }
    var showSmsDialog by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }
    var showManualDialog by remember { mutableStateOf(false) }

    val pickContact = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@rememberLauncherForActivityResult
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0) ?: "Sans nom"
                    val number = c.getString(1) ?: return@use
                    store.addContact(Contact(name, number))
                    contacts = store.contacts
                    onRefresh()
                    Toast.makeText(context, "$name ajouté.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(topBar = { SimpleBar("SMS groupé", back) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {

            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(
                    "Contacts fréquents",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    "Le même SMS sera envoyé à chacune de ces personnes.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                items(contacts) { contact ->
                    ListItem(
                        headlineContent = { Text(contact.name) },
                        supportingContent = { Text(contact.phone) },
                        trailingContent = {
                            IconButton(onClick = {
                                store.removeContact(contact)
                                contacts = store.contacts
                                onRefresh()
                            }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Supprimer")
                            }
                        }
                    )
                    HorizontalDivider()
                }
                if (contacts.isEmpty()) {
                    item {
                        Text(
                            "Aucun contact. Ajoute des personnes depuis ton carnet d'adresses.",
                            modifier = Modifier.padding(20.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Column(
                Modifier.padding(16.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val intent = Intent(
                            Intent.ACTION_PICK,
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI
                        )
                        runCatching { pickContact.launch(intent) }.onFailure {
                            Toast.makeText(
                                context,
                                "Impossible d'ouvrir le carnet d'adresses.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Ajouter depuis le carnet d'adresses") }

                OutlinedButton(
                    onClick = { showManualDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Ajouter un numéro à la main") }

                OutlinedButton(
                    onClick = { showSmsDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Modifier le SMS") }

                Button(
                    onClick = { showConfirm = true },
                    enabled = contacts.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("ENVOYER À TOUS (${contacts.size})", fontWeight = FontWeight.Bold) }
            }
        }
    }

    if (showManualDialog) {
        ContactEditDialog(
            onDismiss = { showManualDialog = false },
            onSave = { name, phone ->
                store.addContact(Contact(name, phone))
                contacts = store.contacts
                onRefresh()
                showManualDialog = false
            }
        )
    }

    if (showSmsDialog) {
        TextEditDialog(
            title = "Texte du SMS",
            helper = "Variables disponibles : {PRENOM} et {MOIS}",
            initial = store.smsInitial,
            onDismiss = { showSmsDialog = false },
            onSave = { store.smsInitial = it; showSmsDialog = false }
        )
    }

    if (showConfirm) {
        val preview = store.smsInitial.fillTemplate(
            contacts.firstOrNull() ?: Contact("Prénom", "")
        )
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Envoyer à ${contacts.size} personnes ?") },
            text = {
                Column {
                    Text("Aperçu du message :", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Text(preview, fontSize = 13.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    val result = SmsService.sendToAll(context, contacts, store.smsInitial)
                    store.lastSendMillis = System.currentTimeMillis()
                    val msg = if (result.failed.isEmpty()) {
                        "${result.sent} SMS envoyés."
                    } else {
                        "${result.sent} envoyés, échec pour : ${result.failed.joinToString()}"
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }) { Text("Envoyer") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Annuler") }
            }
        )
    }
}

/* ---------------------------------------------------------------------- RELANCE */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelanceScreen(store: Store, back: () -> Unit) {
    val context = LocalContext.current
    val contacts = remember { store.contacts }
    var reloadKey by remember { mutableStateOf(0) }

    val statuses = remember(reloadKey) {
        SmsService.statuses(context, contacts, store.lastSendMillis, store.manuallyAnswered)
    }

    var selected by remember { mutableStateOf(emptySet<String>()) }
    var initialized by remember { mutableStateOf(false) }
    var lastSync by remember { mutableStateOf<Long?>(null) }

    // Au premier affichage : tous ceux sans réponse identifiée sont cochés.
    // À chaque synchronisation : on retire ceux qui ont répondu entre-temps,
    // sans effacer les décochages faits à la main.
    LaunchedEffect(statuses) {
        val answeredKeys = statuses
            .filter { it.status == ReplyStatus.ANSWERED }
            .map { PhoneUtils.normalize(it.contact.phone) }
            .toSet()
        selected = if (!initialized) {
            initialized = true
            statuses.filter { it.status != ReplyStatus.ANSWERED }
                .map { PhoneUtils.normalize(it.contact.phone) }
                .toSet()
        } else {
            selected - answeredKeys
        }
    }

    var showSmsDialog by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }

    val neverSent = store.lastSendMillis == 0L
    val answeredCount = statuses.count { it.status == ReplyStatus.ANSWERED }
    val targets = statuses.filter { PhoneUtils.normalize(it.contact.phone) in selected }

    Scaffold(topBar = { SimpleBar("Relance", back) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {

            Card(
                Modifier.padding(16.dp).fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (neverSent)
                        MaterialTheme.colorScheme.errorContainer
                    else
                        MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(Modifier.padding(18.dp)) {
                    if (neverSent) {
                        Text(
                            "Aucun envoi groupé effectué",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Commence par l'écran SMS groupé. Les réponses ne seront " +
                                "recherchées qu'à partir de la date de cet envoi.",
                            fontSize = 13.sp
                        )
                    } else {
                        Text(
                            "À ce jour : $answeredCount réponse(s) sur ${contacts.size} contact(s)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Décoche les personnes que tu ne veux pas relancer.",
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        reloadKey++
                        lastSync = System.currentTimeMillis()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Synchroniser les messages")
                }
            }

            if (lastSync != null) {
                Text(
                    "Dernière synchronisation : " + java.text.SimpleDateFormat("HH:mm:ss", Locale.FRENCH)
                        .format(java.util.Date(lastSync!!)),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${selected.size} sélectionné(s)",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    selected = contacts.map { PhoneUtils.normalize(it.phone) }.toSet()
                }) { Text("Tout cocher") }
                TextButton(onClick = { selected = emptySet() }) { Text("Tout décocher") }
            }

            LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                items(statuses) { cs ->
                    val key = PhoneUtils.normalize(cs.contact.phone)
                    val isChecked = key in selected

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = {
                                selected = if (it) selected + key else selected - key
                            }
                        )
                        Column(Modifier.weight(1f).padding(top = 12.dp)) {
                            Text(cs.contact.name, fontWeight = FontWeight.Medium)
                            Text(
                                cs.contact.phone,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(4.dp))
                            StatusChip(cs.status)

                            if (cs.status == ReplyStatus.UNCLEAR && cs.lastMessage.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "« ${cs.lastMessage.take(140)} »",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (cs.status != ReplyStatus.ANSWERED) {
                                TextButton(
                                    onClick = {
                                        store.markAnswered(cs.contact.phone)
                                        selected = selected - key
                                        reloadKey++
                                    },
                                    contentPadding = PaddingValues(horizontal = 0.dp)
                                ) { Text("Marquer comme répondu", fontSize = 13.sp) }
                            }
                        }
                    }
                    HorizontalDivider()
                }

                if (contacts.isEmpty()) {
                    item {
                        Text(
                            "Aucun contact enregistré.",
                            modifier = Modifier.padding(20.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Column(
                Modifier.padding(16.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { showSmsDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Modifier le SMS de relance") }

                Button(
                    onClick = { showConfirm = true },
                    enabled = targets.isNotEmpty() && !neverSent,
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("ENVOYER LA RELANCE (${targets.size})", fontWeight = FontWeight.Bold) }
            }
        }
    }

    if (showSmsDialog) {
        TextEditDialog(
            title = "Texte de la relance",
            helper = "Variables disponibles : {PRENOM} et {MOIS}",
            initial = store.smsRelance,
            onDismiss = { showSmsDialog = false },
            onSave = { store.smsRelance = it; showSmsDialog = false }
        )
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Relancer ${targets.size} personne(s) ?") },
            text = {
                Column {
                    Text(
                        targets.joinToString(", ") { it.contact.name },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        store.smsRelance.fillTemplate(
                            targets.firstOrNull()?.contact ?: Contact("Prénom", "")
                        ),
                        fontSize = 13.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    val result = SmsService.sendToAll(
                        context, targets.map { it.contact }, store.smsRelance
                    )
                    Toast.makeText(
                        context, "${result.sent} relance(s) envoyée(s).", Toast.LENGTH_LONG
                    ).show()
                    reloadKey++
                }) { Text("Envoyer") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Annuler") }
            }
        )
    }
}

@Composable
fun StatusChip(status: ReplyStatus) {
    val (label, color) = when (status) {
        ReplyStatus.ANSWERED -> "A répondu" to MaterialTheme.colorScheme.primaryContainer
        ReplyStatus.UNCLEAR -> "Message à vérifier" to MaterialTheme.colorScheme.tertiaryContainer
        ReplyStatus.NONE -> "Sans réponse" to MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/* ----------------------------------------------------------------------- EXPORT */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(store: Store, back: () -> Unit) {
    val context = LocalContext.current
    val content = remember { ExportBuilder.build(context, store) }

    Scaffold(topBar = { SimpleBar("Export pour l'IA", back) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {

            Text(
                "Colle ce texte dans ton assistant IA. Il contient déjà la consigne " +
                    "pour produire le planning, la synthèse et la version HTML.",
                modifier = Modifier.padding(20.dp),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Card(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp)) {
                    Text(content, fontSize = 11.sp)
                }
            }

            Column(
                Modifier.padding(16.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        ExportBuilder.copyToClipboard(context, content)
                        Toast.makeText(context, "Export copié.", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("COPIER LE TEXTE", fontWeight = FontWeight.Bold) }

                OutlinedButton(
                    onClick = { ExportBuilder.share(context, content) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Partager le fichier") }

                OutlinedButton(
                    onClick = { ExportBuilder.sendToAdmin(context, store, content) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Envoyer à l'administrateur") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticScreen(store: Store, back: () -> Unit) {
    val context = LocalContext.current
    var reload by remember { mutableStateOf(0) }
    val contacts = remember(reload) { store.contacts }
    val dump = remember(reload) { SmsService.rawInbox(context, contacts) }
    val hasRead = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_SMS
    ) == PackageManager.PERMISSION_GRANTED

    val fmt = remember { java.text.SimpleDateFormat("dd/MM HH:mm", Locale.FRENCH) }

    Scaffold(topBar = { SimpleBar("Diagnostic", back) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("État du système", fontWeight = FontWeight.Bold, fontSize = 16.sp)

            DiagLine("Permission READ_SMS", if (hasRead) "accordée" else "REFUSÉE")
            DiagLine(
                "Dernier envoi groupé",
                if (store.lastSendMillis > 0)
                    fmt.format(java.util.Date(store.lastSendMillis))
                else "jamais"
            )
            DiagLine("Contacts enregistrés", contacts.size.toString())
            DiagLine("SMS dans la boîte de réception", dump.totalInInbox.toString())

            if (dump.error != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Erreur de lecture", fontWeight = FontWeight.Bold)
                        Text(dump.error, fontSize = 12.sp)
                    }
                }
            }

            if (dump.error == null && dump.totalInInbox == 0) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Boîte de réception vide", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Aucun SMS lisible alors que ton téléphone en contient. " +
                                "C'est la signature du RCS : les messages « Chat » de Google " +
                                "Messages ne sont pas stockés dans le fournisseur Telephony " +
                                "et restent invisibles pour cette application. " +
                                "Désactive les discussions RCS dans Google Messages, " +
                                "puis demande un nouveau message.",
                            fontSize = 12.sp
                        )
                    }
                }
            }

            HorizontalDivider()
            Text("Numéros enregistrés", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            contacts.forEach { c ->
                Text(
                    "${c.name} — ${c.phone}  →  clé ${PhoneUtils.normalize(c.phone)}",
                    fontSize = 12.sp
                )
            }
            if (contacts.isEmpty()) {
                Text("Aucun contact.", fontSize = 12.sp)
            }

            HorizontalDivider()
            Text(
                "Derniers SMS lus (${dump.rows.size})",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Text(
                "La clé de l'expéditeur doit correspondre exactement à celle du contact " +
                    "pour que la réponse soit reconnue.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            dump.rows.forEach { r ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (r.matchedContact != null)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            r.matchedContact ?: "expéditeur inconnu",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            "${r.address}  →  clé ${r.normalized}",
                            fontSize = 11.sp
                        )
                        Text(fmt.format(java.util.Date(r.dateMillis)), fontSize = 11.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("« ${r.preview} »", fontSize = 12.sp)
                    }
                }
            }

            Button(
                onClick = { reload++ },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Relire la boîte de réception") }
        }
    }
}

@Composable
fun DiagLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/* ------------------------------------------------------------ COMPOSANTS PARTAGÉS */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleBar(title: String, back: () -> Unit) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.Bold) },
        navigationIcon = {
            IconButton(onClick = back) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Retour")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BigButton(
    title: String,
    subtitle: String,
    primary: Boolean = false,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (primary)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Spacer(Modifier.height(3.dp))
            Text(
                subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun TextEditDialog(
    title: String,
    initial: String,
    helper: String? = null,
    singleLine: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (helper != null) {
                    Text(
                        helper,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = singleLine,
                    minLines = if (singleLine) 1 else 4,
                    modifier = Modifier.fillMaxWidth()
                )
                if (!singleLine) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${value.length} caractères",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
fun ContactEditDialog(
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    val valid = name.isNotBlank() && phone.filter { it.isDigit() }.length >= 9

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nouveau contact") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nom") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Numéro de téléphone") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim(), phone.trim()) }, enabled = valid) {
                Text("Ajouter")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}
