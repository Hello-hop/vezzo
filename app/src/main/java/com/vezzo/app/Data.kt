package com.vezzo.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

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
