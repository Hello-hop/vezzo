package com.vezzo.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

enum class Screen { HOME, SMS_GROUPE, RELANCE, EXPORT }

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askPermissions()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                VezzoApp()
            }
        }
    }

    private fun askPermissions() {
        val needed = listOf(
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 1001)
        }
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
                        val intent = Intent(Intent.ACTION_PICK).apply {
                            type = ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE
                        }
                        pickContact.launch(intent)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Ajouter un contact") }

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
    var pending by remember {
        mutableStateOf(SmsService.nonResponders(context, contacts, store.lastSendMillis))
    }
    var showSmsDialog by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }

    val answered = contacts.size - pending.size

    Scaffold(topBar = { SimpleBar("Relance", back) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {

            Card(
                Modifier.padding(16.dp).fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        "À ce jour : $answered réponse(s) sur ${contacts.size} contact(s)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (store.lastSendMillis == 0L)
                            "Aucun envoi groupé enregistré pour l'instant."
                        else
                            "${pending.size} personne(s) n'ont pas encore répondu.",
                        fontSize = 13.sp
                    )
                }
            }

            Text(
                "En attente de réponse",
                modifier = Modifier.padding(horizontal = 20.dp),
                fontWeight = FontWeight.Bold
            )

            LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                items(pending) { contact ->
                    ListItem(
                        headlineContent = { Text(contact.name) },
                        supportingContent = { Text(contact.phone) }
                    )
                    HorizontalDivider()
                }
                if (pending.isEmpty() && contacts.isNotEmpty()) {
                    item {
                        Text(
                            "Tout le monde a répondu.",
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
                    enabled = pending.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("ENVOYER LA RELANCE (${pending.size})", fontWeight = FontWeight.Bold) }
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
            title = { Text("Relancer ${pending.size} personnes ?") },
            text = {
                Text(store.smsRelance.fillTemplate(pending.firstOrNull() ?: Contact("Prénom", "")))
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    val result = SmsService.sendToAll(context, pending, store.smsRelance)
                    Toast.makeText(
                        context, "${result.sent} relance(s) envoyée(s).", Toast.LENGTH_LONG
                    ).show()
                    pending = SmsService.nonResponders(context, contacts, store.lastSendMillis)
                }) { Text("Envoyer") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Annuler") }
            }
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
