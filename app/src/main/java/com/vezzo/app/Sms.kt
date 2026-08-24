package com.vezzo.app

import android.content.Context
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager

/** Un SMS reçu d'un contact. */
data class Reply(val dateMillis: Long, val body: String)

/** Résultat d'un envoi groupé. */
data class SendResult(val sent: Int, val failed: List<String>)

object SmsService {

    private fun manager(context: Context): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

    /** Envoie un SMS unique (découpé automatiquement s'il dépasse 160 caractères). */
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
     * Une courte pause sépare chaque envoi pour éviter que l'opérateur ne bloque la série.
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
     * Lit tous les SMS reçus depuis [sinceMillis] et les associe aux contacts connus.
     * Les messages venant de numéros inconnus sont ignorés.
     */
    fun readReplies(
        context: Context,
        contacts: List<Contact>,
        sinceMillis: Long
    ): Map<String, List<Reply>> {
        val byContact = linkedMapOf<String, MutableList<Reply>>()
        contacts.forEach { byContact[PhoneUtils.normalize(it.phone)] = mutableListOf() }
        if (contacts.isEmpty()) return emptyMap()

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
                    val key = PhoneUtils.normalize(address)
                    val bucket = byContact[key] ?: continue
                    bucket.add(Reply(cursor.getLong(iDate), cursor.getString(iBody).orEmpty()))
                }
            }
        } catch (e: Exception) {
            // Permission refusée ou fournisseur indisponible : on renvoie des listes vides.
        }

        return byContact
    }

    /** Contacts n'ayant envoyé aucun SMS depuis le dernier envoi groupé. */
    fun nonResponders(
        context: Context,
        contacts: List<Contact>,
        sinceMillis: Long
    ): List<Contact> {
        val replies = readReplies(context, contacts, sinceMillis)
        return contacts.filter { replies[PhoneUtils.normalize(it.phone)].isNullOrEmpty() }
    }
}
