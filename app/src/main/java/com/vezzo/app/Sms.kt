package com.vezzo.app

import android.content.Context
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import java.text.Normalizer

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
                key in manuallyAnswered && list.isNotEmpty() -> ReplyStatus.ANSWERED
                list.isEmpty() -> ReplyStatus.NONE
                list.any { ReplyClassifier.looksLikeAvailability(it.body) } -> ReplyStatus.ANSWERED
                else -> ReplyStatus.UNCLEAR
            }
            ContactStatus(contact, status, list)
        }
    }
}
