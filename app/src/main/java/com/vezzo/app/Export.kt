package com.vezzo.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object ExportBuilder {

    private val stamp: DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd/MM 'à' HH:mm", Locale.FRENCH)

    private val fileStamp: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm", Locale.FRENCH)

    /**
     * Construit le fichier texte destiné à être collé dans une IA.
     * Il contient la consigne de sortie attendue, puis les SMS bruts groupés par personne.
     */
    fun build(context: Context, store: Store): String {
        val contacts = store.contacts
        val since = store.lastSendMillis
        val replies = SmsService.readReplies(context, contacts, since)
        val month = MonthInfo.label()
        val days = MonthInfo.dayCount()
        val answered = contacts.count { !replies[PhoneUtils.normalize(it.phone)].isNullOrEmpty() }
        val zone = ZoneId.systemDefault()

        val sb = StringBuilder()

        sb.appendLine("=== EXPORT VEZZO — DISPONIBILITÉS ===")
        sb.appendLine("Mois à planifier : $month ($days jours)")
        sb.appendLine("Date de l'export : " + stamp.format(Instant.now().atZone(zone)))
        sb.appendLine("Personnes contactées : ${contacts.size}")
        sb.appendLine("Réponses reçues à ce jour : $answered")
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
        sb.appendLine("Les gens répondent en texte libre, sans format imposé. Interprète au mieux :")
        sb.appendLine("- M = matin, A ou PM ou AM = après-midi, J = journée complète.")
        sb.appendLine("- \"3M 5J 12A\" signifie : le 3 au matin, le 5 toute la journée, le 12 l'après-midi.")
        sb.appendLine("- \"dispo le 3 et le 4\" sans précision de créneau = journée complète.")
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

        contacts.forEach { contact ->
            val displayName = if (store.anonymize) contact.firstName else contact.name
            sb.appendLine("### $displayName")
            val list = replies[PhoneUtils.normalize(contact.phone)].orEmpty()
            if (list.isEmpty()) {
                sb.appendLine("(aucune réponse reçue)")
            } else {
                list.forEach { r ->
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
