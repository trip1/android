package com.zaneschepke.wireguardautotunnel.ui.screens.tunnels.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import chat.warpsignal.nebmobile.Nebmobile
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.core.orchestration.DirectPaths
import com.zaneschepke.wireguardautotunnel.ui.LocalNavController
import com.zaneschepke.wireguardautotunnel.ui.navigation.Route
import com.zaneschepke.wireguardautotunnel.ui.common.button.SurfaceRow
import com.zaneschepke.wireguardautotunnel.ui.common.label.GroupLabel
import com.zaneschepke.wireguardautotunnel.ui.common.text.DescriptionText
import com.zaneschepke.wireguardautotunnel.util.extensions.openWebUrl
import com.zaneschepke.wireguardautotunnel.util.network.MeshDirectory
import com.zaneschepke.wireguardautotunnel.util.network.MeshDirectory.PathKind
import com.zaneschepke.wireguardautotunnel.util.network.Nebula
import com.zaneschepke.wireguardautotunnel.util.network.NetworkUtils
import com.zaneschepke.wireguardautotunnel.viewmodel.TunnelViewModel
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import org.orbitmvi.orbit.compose.collectAsState

/** Mesh (gofire Phase 37): services launcher, Wake-on-LAN, per-device path + ping, hub speed test. */
@Composable
fun MeshScreen(viewModel: TunnelViewModel) {
    val uiState by viewModel.collectAsState()
    val tunnel = uiState.tunnel ?: return
    val hub = remember(tunnel) { DirectPaths.hubOf(tunnel) } ?: return
    val dir = DirectPaths.directories.collectAsState().value[tunnel.id]
    val direct = DirectPaths.direct.collectAsState().value[tunnel.id].orEmpty()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val networkUtils: NetworkUtils = koinInject()
    val profile = remember(tunnel.nebulaProfile) { tunnel.nebulaProfile.takeIf { it.isNotEmpty() }?.let(Nebula::parse) }
    val toast = { msg: String -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }

    Column(
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top),
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        if (dir == null) DescriptionText(stringResource(R.string.mesh_waiting), Modifier.padding(horizontal = 16.dp))
        val navController = LocalNavController.current
        // Phase 38: only for clients the hub marks admin (directory self.admin)
        if (dir?.selfAdmin == true) {
            SurfaceRow(
                leading = { Icon(Icons.Outlined.AdminPanelSettings, contentDescription = null) },
                title = stringResource(R.string.admin_title),
                description = { DescriptionText(stringResource(R.string.admin_desc)) },
                onClick = { navController.push(Route.MeshAdmin(tunnel.id)) },
            )
        }
        SurfaceRow(
            leading = { Icon(Icons.Outlined.Map, contentDescription = null) },
            title = stringResource(R.string.map_title),
            description = { DescriptionText(stringResource(R.string.map_desc)) },
            onClick = { navController.push(Route.MeshMap(tunnel.id)) },
        )
        dir?.services?.takeIf { it.isNotEmpty() }?.let { services ->
            Column {
                GroupLabel(stringResource(R.string.mesh_services), modifier = Modifier.padding(horizontal = 16.dp))
                services.forEach { s ->
                    SurfaceRow(
                        leading = { Icon(Icons.Outlined.Link, contentDescription = null) },
                        title = s.name,
                        description = { DescriptionText(s.url) },
                        onClick = { context.openWebUrl(s.url) },
                    )
                }
            }
        }
        dir?.wol?.takeIf { it.isNotEmpty() }?.let { machines ->
            Column {
                GroupLabel(stringResource(R.string.mesh_wol), modifier = Modifier.padding(horizontal = 16.dp))
                machines.forEach { w ->
                    SurfaceRow(
                        leading = { Icon(Icons.Outlined.PowerSettingsNew, contentDescription = null) },
                        title = stringResource(R.string.mesh_wake, w.name),
                        onClick = {
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { runCatching { MeshDirectory.wake(hub, w) } }
                                toast(r.fold({ context.getString(R.string.mesh_wake_sent, w.name) },
                                    { context.getString(R.string.mesh_failed, it.message.orEmpty()) }))
                            }
                        },
                    )
                }
            }
        }
        Column {
            GroupLabel(stringResource(R.string.mesh_speed_test), modifier = Modifier.padding(horizontal = 16.dp))
            var result by remember { mutableStateOf<String?>(null) }
            var warpMbps by remember { mutableStateOf<Double?>(null) }
            WarpGauge(warpMbps)
            SurfaceRow(
                leading = { Icon(Icons.Outlined.NetworkCheck, contentDescription = null) },
                title = stringResource(R.string.mesh_speed_run),
                description = { result?.let { DescriptionText(it) } },
                onClick = {
                    result = context.getString(R.string.mesh_speed_running)
                    scope.launch {
                        result = withContext(Dispatchers.IO) {
                            runCatching {
                                val down = MeshDirectory.speed(hub, up = false).also { warpMbps = it }
                                val up = MeshDirectory.speed(hub, up = true)
                                context.getString(R.string.mesh_speed_result, down, up)
                            }.getOrElse {
                                if (it.message?.contains("429") == true) context.getString(R.string.mesh_rate_limited)
                                else context.getString(R.string.mesh_failed, it.message.orEmpty())
                            }
                        }
                    }
                },
            )
        }
        dir?.let { d ->
            Column {
                GroupLabel(stringResource(R.string.mesh_paths), modifier = Modifier.padding(horizontal = 16.dp))
                val names = d.devices.associate { it.meshIp to it.name } + profile?.lighthouses.orEmpty().associate { it.ip to it.name }
                val lighthouses = profile?.lighthouses.orEmpty().map { it.ip }.toSet() + hub
                val peers = uiState.activeConfig?.peers.orEmpty()
                d.devices.filter { it.meshIp != d.selfIp && it.online }.forEach { dev ->
                    // Nebula member on a Nebula tunnel: its hostmap remote / relay; a WireGuard Direct Paths peer: direct;
                    // anything else crosses the hub
                    val endpoint = when {
                        profile != null && dev.kind == "nebula" -> peers.firstOrNull { p ->
                            p.allowedIPs.orEmpty().split(',').any { a -> a.trim().substringBefore('/') in dev.addresses }
                        }?.endpoint
                        profile == null && dev.publicKey in direct -> dev.endpoint
                        else -> "relay $hub"
                    }
                    val (kind, via) = MeshDirectory.pathOf(endpoint, dev.meshIp in lighthouses || dev.kind == "node", names)
                    val path = when (kind) {
                        PathKind.DIRECT -> stringResource(R.string.mesh_path_direct)
                        PathKind.RELAYED -> stringResource(R.string.mesh_path_relayed, via.orEmpty())
                        PathKind.LIGHTHOUSE -> stringResource(R.string.mesh_path_lighthouse)
                        PathKind.UNKNOWN -> stringResource(R.string.mesh_path_unknown)
                    }
                    val rtt by produceState<Double?>(null, dev.reachable) {
                        while (true) {
                            value = if (profile != null) // the app's sockets bypass the engine's ping path
                                withContext(Nebula.dispatcher) { runCatching { Nebmobile.ping(dev.reachable, 2000).toDouble() }.getOrNull() }?.takeIf { it >= 0 }
                            else runCatching { networkUtils.pingWithStats(dev.reachable, 1) }.getOrNull()?.takeIf { it.isReachable }?.rttAvg
                            delay(10.seconds)
                        }
                    }
                    SurfaceRow(
                        leading = { Icon(Icons.Outlined.Route, contentDescription = null) },
                        title = "${dev.name} · ${dev.reachable}",
                        description = {
                            DescriptionText("$path · " + stringResource(R.string.rtt_template,
                                rtt?.let { "${it.toInt()} ms" } ?: stringResource(R.string.ping_unreachable)))
                        },
                    )
                }
            }
        }
    }
}
