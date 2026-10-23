package com.zaneschepke.wireguardautotunnel

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.zaneschepke.wireguardautotunnel.core.orchestration.DirectPaths
import com.zaneschepke.wireguardautotunnel.core.orchestration.TunnelCoordinator
import com.zaneschepke.wireguardautotunnel.ui.theme.AlertRed
import com.zaneschepke.wireguardautotunnel.ui.theme.SilverTree
import com.zaneschepke.wireguardautotunnel.ui.common.dialog.InfoDialog
import com.zaneschepke.wireguardautotunnel.ui.common.textbox.ConfigurationTextBox
import com.zaneschepke.wireguardautotunnel.ui.theme.WireguardAutoTunnelTheme
import com.zaneschepke.wireguardautotunnel.util.network.Beam
import com.zaneschepke.wireguardautotunnel.util.network.MeshEnroll
import com.zaneschepke.wireguardautotunnel.util.network.firstAllowedHost
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import timber.log.Timber

/** "Beam via Transwarp" share target: PUTs the shared text/file to a mesh peer's Beam receiver. */
class BeamActivity : ComponentActivity() {
    private val tunnelCoordinator: TunnelCoordinator by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action != Intent.ACTION_SEND) return finish()
        setContent {
            WireguardAutoTunnelTheme {
                val status by tunnelCoordinator.backendStatus.collectAsState()
                val directory = DirectPaths.directories.collectAsState().value
                    .filterKeys { it in status.activeTunnels }.values.firstOrNull()
                // host -> label: hoisted gofire peer names resolve as <name>.mesh via MagicDNS
                val peers =
                    if (directory != null) emptyList() else status.activeTunnels.values
                        .flatMap { t ->
                            t.activeConfig?.peers.orEmpty().mapNotNull { p ->
                                firstAllowedHost(p.allowedIPs)?.let { host ->
                                    host to (t.mode?.config?.headerComments
                                        ?.let { MeshEnroll.peerName(it, p.publicKey) }?.let { "$it.mesh" } ?: host)
                                }
                            }
                        }
                        .distinct()
                var manual by remember { mutableStateOf("") }
                InfoDialog(
                    onDismiss = { finish() },
                    title = stringResource(R.string.beam_via_transwarp),
                    body = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (peers.isEmpty() && directory == null) Text(stringResource(R.string.beam_no_peers))
                            peers.forEach { (host, label) -> TextButton(onClick = { beam(host) }) { Text(label) } }
                            directory?.devices?.filter { it.meshIp != directory.selfIp }?.forEach { d ->
                                TextButton(onClick = { beam(d.meshIp) }) {
                                    Icon(
                                        Icons.Rounded.Circle,
                                        contentDescription = stringResource(if (d.online) R.string.peer_online else R.string.peer_offline),
                                        tint = if (d.online) SilverTree else AlertRed,
                                        modifier = Modifier.size(12.dp),
                                    )
                                    Text("  ${d.name}.mesh  ${d.meshIp}")
                                }
                            }
                            ConfigurationTextBox(
                                value = manual,
                                label = stringResource(R.string.beam_manual_ip),
                                hint = "pi-5-1.mesh",
                                onValueChange = { manual = it.trim() },
                            )
                        }
                    },
                    confirmText = stringResource(R.string.beam_send),
                    confirmEnabled = manual.isNotEmpty(),
                    onAttest = { beam(manual) },
                )
            }
        }
    }

    private fun beam(host: String) {
        val appContext = applicationContext
        lifecycleScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                        when {
                            uri != null -> sendUri(host, uri)
                            text != null -> text.toByteArray().let {
                                Beam.send(host, "beam-${System.currentTimeMillis()}.txt", it.size.toLong(), it.inputStream())
                            }
                            else -> error("nothing to send")
                        }
                    }
                }
            val ok = result.getOrNull() == 201
            result.exceptionOrNull()?.let { Timber.e(it, "Beam send failed") }
            Toast.makeText(
                    appContext,
                    if (ok) getString(R.string.beam_sent, host)
                    else getString(R.string.beam_failed, result.exceptionOrNull()?.message ?: "HTTP ${result.getOrNull()}"),
                    Toast.LENGTH_LONG,
                )
                .show()
            finish()
        }
    }

    private fun sendUri(host: String, uri: Uri): Int {
        var name: String? = null
        var size: Long? = null
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
            }
        }
        val fileName = name ?: uri.lastPathSegment ?: "beam-${System.currentTimeMillis()}"
        size?.let { len ->
            return contentResolver.openInputStream(uri)!!.use { Beam.send(host, fileName, len, it) }
        }
        // Unknown size: spool to cache so Content-Length is exact.
        val tmp = File.createTempFile("beam", null, cacheDir)
        try {
            contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            return tmp.inputStream().use { Beam.send(host, fileName, tmp.length(), it) }
        } finally {
            tmp.delete()
        }
    }
}
