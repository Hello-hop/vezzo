package com.vezzo.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

enum class Screen { HOME, SMS_GROUPE, RELANCE, EXPORT }

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

    // Pré-cochés : tous ceux dont on n'a pas identifié de réponse.
    var selected by remember(reloadKey) {
        mutableStateOf(
            statuses.filter { it.status != ReplyStatus.ANSWERED }
                .map { PhoneUtils.normalize(it.contact.phone) }
                .toSet()
        )
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
