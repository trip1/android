package com.zaneschepke.wireguardautotunnel.ui.screens.tunnels.settings

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.PersistableBundle
import android.widget.Toast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Celebration
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.datastore.preferences.core.stringPreferencesKey
import com.zaneschepke.wireguardautotunnel.data.DataStoreManager
import com.zaneschepke.wireguardautotunnel.util.Party
import org.koin.compose.koinInject
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.core.orchestration.DirectPaths
import com.zaneschepke.wireguardautotunnel.ui.common.button.SurfaceRow
import com.zaneschepke.wireguardautotunnel.ui.common.button.ThemedSwitch
import com.zaneschepke.wireguardautotunnel.ui.common.dialog.InfoDialog
import com.zaneschepke.wireguardautotunnel.ui.common.dropdown.DropdownSelector
import com.zaneschepke.wireguardautotunnel.ui.common.label.GroupLabel
import com.zaneschepke.wireguardautotunnel.ui.common.text.DescriptionText
import com.zaneschepke.wireguardautotunnel.ui.common.textbox.ConfigurationTextBox
import com.zaneschepke.wireguardautotunnel.ui.screens.tunnels.settings.config.components.createQrOptions
import com.zaneschepke.wireguardautotunnel.util.extensions.setScreenBrightness
import com.zaneschepke.wireguardautotunnel.util.network.MeshAdmin
import com.zaneschepke.wireguardautotunnel.viewmodel.TunnelViewModel
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.orbitmvi.orbit.compose.collectAsState

private tailrec fun Context.activity(): FragmentActivity? =
    this as? FragmentActivity ?: (this as? ContextWrapper)?.baseContext?.activity()

/** BiometricPrompt (fingerprint/face or the device PIN/pattern); false if cancelled or no screen lock is set. */
private suspend fun authenticate(activity: FragmentActivity): Boolean {
    val auth = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    if (BiometricManager.from(activity).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) return false
    return suspendCancellableCoroutine { cont ->
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { if (cont.isActive) cont.resume(true) }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { if (cont.isActive) cont.resume(false) }
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.admin_confirm))
            .setAllowedAuthenticators(auth)
            .build())
        cont.invokeOnCancellation { prompt.cancelAuthentication() }
    }
}

/** Mesh admin (gofire Phase 38): the hub's `/v1/mesh/admin/` API; every change asks for the screen lock first. */
@Composable
fun MeshAdminScreen(viewModel: TunnelViewModel) {
    val uiState by viewModel.collectAsState()
    val tunnel = uiState.tunnel ?: return
    val hub = remember(tunnel) { DirectPaths.hubOf(tunnel) } ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast = { msg: String -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }

    var refresh by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var clients by remember { mutableStateOf(emptyList<MeshAdmin.Client>()) }
    var rules by remember { mutableStateOf(emptyList<MeshAdmin.Rule>()) }
    var wol by remember { mutableStateOf(emptyList<MeshAdmin.WolHost>()) }
    var services by remember { mutableStateOf(emptyList<Pair<com.zaneschepke.wireguardautotunnel.util.network.MeshDirectory.Service, Boolean>>()) }
    // (client name, join code): held only in composition state, shown as a QR, never logged
    var code by remember { mutableStateOf<Pair<String, String>?>(null) }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<MeshAdmin.Client?>(null) }
    var form by remember { mutableStateOf<String?>(null) } // "rule" | "wol" | "service"
    // LAN party: guest names this phone created (DataStore) and the codes still to show, one QR at a time
    val dataStore: DataStoreManager = koinInject()
    val partyNames by dataStore.getFromStoreFlow(PARTY_KEY).collectAsState(initial = null)
    var partyCodes by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var partyForm by remember { mutableStateOf(false) }

    LaunchedEffect(refresh) {
        val errs = mutableListOf<String>()
        withContext(Dispatchers.IO) {
            coroutineScope {
                val cJob = async { runCatching { MeshAdmin.parseClients(MeshAdmin.call(hub, "GET", MeshAdmin.path("clients"))) } }
                val rJob = async { runCatching { MeshAdmin.parseRules(MeshAdmin.call(hub, "GET", MeshAdmin.path("nebula", "rules"))) } }
                val wJob = async { runCatching { MeshAdmin.parseWol(MeshAdmin.call(hub, "GET", MeshAdmin.path("wol-hosts"))) } }
                val sJob = async { runCatching { MeshAdmin.parseServices(MeshAdmin.call(hub, "GET", MeshAdmin.path("services"))) } }
                cJob.await().onSuccess { clients = it }.onFailure { errs += it.message.orEmpty() }
                rJob.await().onSuccess { rules = it }.onFailure { errs += it.message.orEmpty() }
                wJob.await().onSuccess { wol = it }.onFailure { errs += it.message.orEmpty() }
                sJob.await().onSuccess { services = it }.onFailure { errs += it.message.orEmpty() }
            }
        }
        error = errs.firstOrNull { it.isNotBlank() }
    }

    /** Screen lock first, then the call; a returned join code opens the QR dialog. */
    fun mutate(method: String, path: String, json: String? = null, codeFor: String? = null) {
        val activity = context.activity() ?: return
        scope.launch {
            if (!authenticate(activity)) {
                toast(context.getString(
                    if (BiometricManager.from(activity).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS)
                        R.string.admin_cancelled else R.string.admin_need_lock))
                return@launch
            }
            withContext(Dispatchers.IO) { runCatching { MeshAdmin.call(hub, method, path, json) } }
                .onSuccess { body -> if (codeFor != null) runCatching { code = codeFor to MeshAdmin.codeOf(body) } }
                .onFailure { toast(context.getString(R.string.mesh_failed, it.message.orEmpty())) }
            refresh++
        }
    }

    /** Start party: one screen-lock prompt, then a Nebula guest per name (no lan/exit/peers flags, no firewall rules). */
    fun startParty(names: List<String>, ttl: String) {
        val activity = context.activity() ?: return
        scope.launch {
            if (!authenticate(activity)) return@launch toast(context.getString(R.string.admin_cancelled))
            val codes = mutableListOf<Pair<String, String>>()
            for (n in names) {
                val r = withContext(Dispatchers.IO) {
                    runCatching { MeshAdmin.codeOf(MeshAdmin.call(hub, "POST", MeshAdmin.path("clients"), MeshAdmin.NewClient(n, nebula = true, ttl = ttl).json())) }
                }
                r.onSuccess { codes += n to it }.onFailure { toast(context.getString(R.string.mesh_failed, it.message.orEmpty())) }
                // track as soon as it exists on the hub, so End party can revoke it even if a later create fails
                if (r.isSuccess) dataStore.saveToDataStore(PARTY_KEY, Party.encode(Party.decode(dataStore.getFromStore(PARTY_KEY)) + n))
                if (r.isFailure) break
            }
            partyCodes = partyCodes + codes
            refresh++
        }
    }

    /** End party: revoke every tracked party-* name the hub still lists; failures stay tracked for the next tap. */
    fun endParty() {
        val activity = context.activity() ?: return
        scope.launch {
            if (!authenticate(activity)) return@launch toast(context.getString(R.string.admin_cancelled))
            val tracked = Party.decode(dataStore.getFromStore(PARTY_KEY))
            val left = withContext(Dispatchers.IO) {
                runCatching {
                    val onHub = MeshAdmin.parseClients(MeshAdmin.call(hub, "GET", MeshAdmin.path("clients"))).map { it.name }
                    Party.revokeList(tracked, onHub).filter { n -> runCatching { MeshAdmin.call(hub, "DELETE", MeshAdmin.path("clients", n)) }.isFailure }
                }
            }.onFailure { toast(context.getString(R.string.mesh_failed, it.message.orEmpty())) }.getOrDefault(tracked)
            if (left.isEmpty()) dataStore.removeFromDataStore(PARTY_KEY) else {
                dataStore.saveToDataStore(PARTY_KEY, Party.encode(left))
                toast(context.getString(R.string.party_left, left.size))
            }
            refresh++
        }
    }

    Column(
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top),
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        Column {
            GroupLabel(stringResource(R.string.party_title), modifier = Modifier.padding(horizontal = 16.dp))
            val tracked = Party.decode(partyNames)
            if (tracked.isEmpty()) {
                SurfaceRow(leading = { Icon(Icons.Outlined.Celebration, contentDescription = null) },
                    title = stringResource(R.string.party_start), onClick = { partyForm = true })
            } else {
                SurfaceRow(leading = { Icon(Icons.Outlined.Celebration, contentDescription = null) },
                    title = stringResource(R.string.party_end), description = { DescriptionText(tracked.joinToString(", ")) },
                    onClick = { endParty() })
            }
            DescriptionText(stringResource(R.string.party_help), Modifier.padding(horizontal = 16.dp))
        }
        error?.let { DescriptionText(stringResource(R.string.mesh_failed, it), Modifier.padding(horizontal = 16.dp)) }
        Column {
            GroupLabel(stringResource(R.string.admin_clients), modifier = Modifier.padding(horizontal = 16.dp))
            clients.forEach { c ->
                SurfaceRow(
                    leading = { Icon(Icons.Outlined.Person, contentDescription = null) },
                    title = c.name,
                    description = {
                        DescriptionText(listOfNotNull(c.kind, c.meshIp, "lan".takeIf { c.lan }, "exit".takeIf { c.exit },
                            "peers".takeIf { c.peers }, "admin".takeIf { c.admin }, c.groups.joinToString(",").ifEmpty { null },
                            c.expiresAt?.take(10)?.let { "exp $it" }, stringResource(R.string.admin_not_enrolled).takeIf { !c.enrolled },
                        ).joinToString(" · "))
                    },
                    onClick = { editing = c },
                )
            }
            AddRow(stringResource(R.string.admin_add_client)) { adding = true }
        }
        Column {
            GroupLabel(stringResource(R.string.admin_rules), modifier = Modifier.padding(horizontal = 16.dp))
            rules.forEach { r ->
                RemovableRow("${r.from} → ${r.to}", "${r.proto}/${r.port}") { mutate("DELETE", MeshAdmin.path("nebula", "rules", r.id)) }
            }
            AddRow(stringResource(R.string.admin_add_rule)) { form = "rule" }
        }
        Column {
            GroupLabel(stringResource(R.string.mesh_wol), modifier = Modifier.padding(horizontal = 16.dp))
            wol.forEach { w -> RemovableRow(w.name, w.mac) { mutate("DELETE", MeshAdmin.path("wol-hosts", w.name)) } }
            AddRow(stringResource(R.string.admin_add_wol)) { form = "wol" }
        }
        Column {
            GroupLabel(stringResource(R.string.mesh_services), modifier = Modifier.padding(horizontal = 16.dp))
            services.forEach { (s, manual) ->
                RemovableRow(s.name, s.url, removable = manual) { mutate("DELETE", MeshAdmin.path("services", s.name)) }
            }
            AddRow(stringResource(R.string.admin_add_service)) { form = "service" }
        }
    }

    if (adding) AddClientDialog(onDismiss = { adding = false }) { nc ->
        adding = false
        mutate("POST", MeshAdmin.path("clients"), nc.json(), codeFor = nc.name)
    }
    editing?.let { c ->
        EditClientDialog(c, onDismiss = { editing = null },
            onSave = { json -> editing = null; mutate("PATCH", MeshAdmin.path("clients", c.name), json) },
            onRenew = { editing = null; mutate("POST", MeshAdmin.path("clients", c.name, "renew"), "{}", codeFor = c.name) },
            onRevoke = { editing = null; mutate("DELETE", MeshAdmin.path("clients", c.name)) })
    }
    form?.let { kind ->
        val (title, fields) = when (kind) {
            "rule" -> R.string.admin_add_rule to R.string.admin_rule_fields
            "wol" -> R.string.admin_add_wol to R.string.admin_wol_fields
            else -> R.string.admin_add_service to R.string.admin_service_fields
        }
        FieldsDialog(stringResource(title), stringResource(fields).split('|'), onDismiss = { form = null }) { v ->
            form = null
            when (kind) {
                "rule" -> mutate("POST", MeshAdmin.path("nebula", "rules"), MeshAdmin.ruleJson(v[0], v[1], v[2], v[3]))
                "wol" -> mutate("PUT", MeshAdmin.path("wol-hosts", v[0]), MeshAdmin.wolJson(v[1]))
                else -> mutate("POST", MeshAdmin.path("services"), MeshAdmin.serviceJson(v[0], v[1]))
            }
        }
    }
    code?.let { (name, c) -> CodeDialog(name, c) { code = null } }
    partyCodes.firstOrNull()?.let { (name, c) -> CodeDialog(name, c) { partyCodes = partyCodes.drop(1) } }
    if (partyForm) PartyDialog(onDismiss = { partyForm = false }) { names, ttl -> partyForm = false; startParty(names, ttl) }
}

private val PARTY_KEY = stringPreferencesKey("PARTY_GUESTS")

@Composable
private fun PartyDialog(onDismiss: () -> Unit, onStart: (List<String>, String) -> Unit) {
    var word by remember { mutableStateOf(Party.WORDS.random()) }
    var count by remember { mutableStateOf("2") }
    var ttl by remember { mutableStateOf(Party.TTLS.first()) }
    var ttlOpen by remember { mutableStateOf(false) }
    val names = Party.names(word, count.toIntOrNull() ?: 2)
    InfoDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.party_start),
        body = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ConfigurationTextBox(value = word, label = stringResource(R.string.party_word), hint = "nova",
                    onValueChange = { word = it.trim().lowercase() })
                ConfigurationTextBox(value = count, label = stringResource(R.string.party_count, Party.MAX_GUESTS), hint = "2",
                    onValueChange = { count = it.filter(Char::isDigit).take(1) })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.admin_ttl) + "  ")
                    DropdownSelector(ttl, Party.TTLS, onValueSelected = { ttl = it ?: Party.TTLS.first() }, isExpanded = ttlOpen,
                        onDismiss = { ttlOpen = false }, modifier = Modifier.clickable { ttlOpen = true }.padding(4.dp),
                        optionToString = { it.orEmpty() })
                }
                DescriptionText(names.joinToString(", "))
            }
        },
        confirmText = stringResource(R.string.party_go),
        onAttest = { onStart(names, ttl) },
    )
}

@Composable
private fun AddRow(title: String, onClick: () -> Unit) =
    SurfaceRow(leading = { Icon(Icons.Outlined.Add, contentDescription = null) }, title = title, onClick = onClick)

@Composable
private fun RemovableRow(title: String, desc: String, removable: Boolean = true, onRemove: () -> Unit) =
    SurfaceRow(
        title = title,
        description = { DescriptionText(desc) },
        trailing = if (!removable) null else removeButton(onRemove),
    )

private fun removeButton(onRemove: () -> Unit): @Composable (Modifier) -> Unit = { m ->
    IconButton(onClick = onRemove, modifier = m) {
        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.admin_remove))
    }
}

@Composable
private fun SwitchLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f))
        ThemedSwitch(checked, onClick = onChange)
    }

private fun groupsOf(s: String) = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }

@Composable
private fun AddClientDialog(onDismiss: () -> Unit, onCreate: (MeshAdmin.NewClient) -> Unit) {
    var name by remember { mutableStateOf("") }
    var nebula by remember { mutableStateOf(true) }
    var lan by remember { mutableStateOf(false) }
    var exit by remember { mutableStateOf(false) }
    var peers by remember { mutableStateOf(false) }
    var groups by remember { mutableStateOf("") }
    var ttl by remember { mutableStateOf("90d") }
    var ttlOpen by remember { mutableStateOf(false) }
    InfoDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.admin_add_client),
        body = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ConfigurationTextBox(value = name, label = stringResource(R.string.name), hint = "guest-phone",
                    onValueChange = { name = it.trim().lowercase() })
                SwitchLine(stringResource(R.string.admin_nebula), nebula) { nebula = it }
                SwitchLine(stringResource(R.string.admin_lan), lan) { lan = it }
                SwitchLine(stringResource(R.string.admin_exit), exit) { exit = it }
                if (!nebula) SwitchLine(stringResource(R.string.admin_peers), peers) { peers = it }
                if (nebula) ConfigurationTextBox(value = groups, label = stringResource(R.string.admin_groups), hint = "laptops",
                    onValueChange = { groups = it })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.admin_ttl) + "  ")
                    DropdownSelector(ttl, MeshAdmin.TTLS, onValueSelected = { ttl = it ?: "90d" }, isExpanded = ttlOpen,
                        onDismiss = { ttlOpen = false }, modifier = Modifier.clickable { ttlOpen = true }.padding(4.dp),
                        optionToString = { it.orEmpty() })
                }
                TextButton(onClick = { onCreate(MeshAdmin.guest(name, nebula)) }, enabled = name.isNotEmpty()) {
                    Text(stringResource(R.string.admin_guest))
                }
            }
        },
        confirmText = stringResource(R.string.admin_create),
        confirmEnabled = name.isNotEmpty(),
        onAttest = { onCreate(MeshAdmin.NewClient(name, nebula, lan, exit, peers && !nebula, groupsOf(groups), ttl)) },
    )
}

@Composable
private fun EditClientDialog(
    c: MeshAdmin.Client,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onRenew: () -> Unit,
    onRevoke: () -> Unit,
) {
    var lan by remember { mutableStateOf(c.lan) }
    var exit by remember { mutableStateOf(c.exit) }
    var peers by remember { mutableStateOf(c.peers) }
    var groups by remember { mutableStateOf(c.groups.joinToString(",")) }
    InfoDialog(
        onDismiss = onDismiss,
        title = c.name,
        body = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SwitchLine(stringResource(R.string.admin_lan), lan) { lan = it }
                SwitchLine(stringResource(R.string.admin_exit), exit) { exit = it }
                if (!c.nebula) SwitchLine(stringResource(R.string.admin_peers), peers) { peers = it }
                if (c.nebula) ConfigurationTextBox(value = groups, label = stringResource(R.string.admin_groups), hint = "laptops",
                    onValueChange = { groups = it })
                Row {
                    TextButton(onClick = onRenew) { Text(stringResource(R.string.admin_renew)) }
                    TextButton(onClick = onRevoke) { Text(stringResource(R.string.admin_revoke), color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmText = stringResource(R.string.save),
        onAttest = { onSave(MeshAdmin.patchJson(lan, exit, peers.takeIf { !c.nebula }, groupsOf(groups).takeIf { c.nebula })) },
    )
}

@Composable
private fun FieldsDialog(title: String, labels: List<String>, onDismiss: () -> Unit, onDone: (List<String>) -> Unit) {
    var values by remember { mutableStateOf(List(labels.size) { "" }) }
    InfoDialog(
        onDismiss = onDismiss,
        title = title,
        body = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                labels.forEachIndexed { i, l ->
                    ConfigurationTextBox(value = values[i], label = l, hint = l,
                        onValueChange = { v -> values = values.toMutableList().also { it[i] = v.trim() } })
                }
            }
        },
        confirmText = stringResource(R.string.add),
        confirmEnabled = values.first().isNotEmpty(),
        onAttest = { onDone(values) },
    )
}

/** The join code as a QR (same renderer as the config QR) plus copy (marked sensitive) and share. */
@Composable
private fun CodeDialog(name: String, code: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val a = context.activity()
        a?.setScreenBrightness(1.0f)
        onDispose { a?.setScreenBrightness(-1f) }
    }
    AlertDialog(
        containerColor = Color.White,
        onDismissRequest = onDismiss,
        title = { Text(name, color = Color.Black) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painter = rememberQrCodePainter(data = code, options = createQrOptions()),
                    contentDescription = stringResource(R.string.show_qr),
                    modifier = Modifier.size(280.dp).padding(8.dp).background(Color.White),
                )
                Text(stringResource(R.string.admin_code_hint), color = Color.Black, style = MaterialTheme.typography.bodySmall)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    val clip = ClipData.newPlainText(name, code).apply {
                        description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                    }
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                }) { Text(stringResource(R.string.copy), color = Color.Black) }
                TextButton(onClick = {
                    context.startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, code), name))
                }) { Text(stringResource(R.string.admin_share), color = Color.Black) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done), color = Color.Black) } },
    )
}
