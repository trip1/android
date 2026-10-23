package com.zaneschepke.wireguardautotunnel.ui.screens.tunnels.components

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wgtunnel.parser.ActiveConfig
import com.wgtunnel.parser.Config
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.core.orchestration.DirectPaths
import com.zaneschepke.wireguardautotunnel.ui.common.button.SurfaceRow
import com.zaneschepke.wireguardautotunnel.ui.common.functions.rememberClipboardHelper
import com.zaneschepke.wireguardautotunnel.ui.theme.AlertRed
import com.zaneschepke.wireguardautotunnel.ui.theme.LcarsCrimson
import com.zaneschepke.wireguardautotunnel.ui.theme.SilverTree
import com.zaneschepke.wireguardautotunnel.util.extensions.abbreviateKey
import com.zaneschepke.wireguardautotunnel.util.extensions.toAgoDisplay
import com.zaneschepke.wireguardautotunnel.util.network.NetworkUtils
import com.zaneschepke.wireguardautotunnel.util.network.MeshEnroll
import com.zaneschepke.wireguardautotunnel.util.network.firstAllowedHost
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

private const val OFFLINE_AFTER_SECONDS = 180L

/** Key expiry from `# Transwarp-Expires`; LCARS crimson under 7 days or once expired. */
@Composable
fun ExpiryText(days: Long) {
    Text(
        text = if (days < 0) stringResource(R.string.key_expired) else stringResource(R.string.key_expires_in, days),
        style = MaterialTheme.typography.bodySmall,
        color = if (days < 7) LcarsCrimson else MaterialTheme.colorScheme.outline,
    )
}

/** Mesh Sensor Array: every peer of an active tunnel with handshake age, traffic and live RTT. */
@Composable
fun MeshSensorArray(active: ActiveConfig, stored: Config?, route: String? = null, tunnelId: Int = -1) {
    val directory = DirectPaths.directories.collectAsState().value[tunnelId]
    val direct = DirectPaths.direct.collectAsState().value[tunnelId].orEmpty()
    val context = LocalContext.current
    val clipboard = rememberClipboardHelper()
    val networkUtils: NetworkUtils = koinInject()
    val now by
        produceState(System.currentTimeMillis()) {
            while (true) {
                delay(1.seconds)
                value = System.currentTimeMillis()
            }
        }
    val style = MaterialTheme.typography.bodySmall
    val color = MaterialTheme.colorScheme.outline
    stored?.let { MeshEnroll.expiresInDays(it.asQuickString()) }?.let { ExpiryText(it) }
    active.peers.forEach { peer ->
        // stored routes: under Full Warp the running peer's first route is 0.0.0.0/0
        val host =
            firstAllowedHost(
                stored?.peers?.firstOrNull { it.publicKey == peer.publicKey }?.allowedIPs
                    ?: peer.allowedIPs
            )
        val storedPeer = stored?.peers?.firstOrNull { it.publicKey == peer.publicKey }
        val name = stored?.let { MeshEnroll.peerName(it.headerComments, peer.publicKey) }
            ?: directory?.devices?.firstOrNull { it.publicKey == peer.publicKey }?.name
        val lan = MeshEnroll.lanRoutes(storedPeer?.allowedIPs)
        // hub peer carries the client range: online peers clients not on a direct path go through it
        val viaHub = if (storedPeer?.allowedIPs?.contains("10.89.200.0/21") == true)
            directory?.devices?.filter { it.kind == "client" && it.online && it.meshIp != directory.selfIp && it.publicKey !in direct }
                ?.map { it.name }.orEmpty() else emptyList()
        val rtt by
            produceState<Double?>(null, host) {
                if (host == null) return@produceState
                while (true) {
                    value =
                        runCatching { networkUtils.pingWithStats(host, 1) }
                            .getOrNull()
                            ?.takeIf { it.isReachable }
                            ?.rttAvg
                    delay(10.seconds)
                }
            }
        val handshake = peer.lastHandshakeSeconds?.takeIf { it > 0 }
        val online = handshake != null && now / 1000 - handshake <= OFFLINE_AFTER_SECONDS
        SurfaceRow(
            leading = {
                Icon(
                    Icons.Rounded.Circle,
                    contentDescription =
                        stringResource(if (online) R.string.peer_online else R.string.peer_offline),
                    tint = if (online) SilverTree else AlertRed,
                    modifier = Modifier.size(14.dp),
                )
            },
            title = name ?: host ?: peer.publicKey.abbreviateKey(),
            description = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (name != null && host != null) StatText(host, style, color)
                    if (peer.publicKey in direct) StatText(stringResource(R.string.path_direct), style, SilverTree)
                    if (lan.isNotEmpty()) StatText(stringResource(R.string.lan_template, lan.joinToString()), style, color)
                    if (viaHub.isNotEmpty()) StatText(stringResource(R.string.via_hub_template, viaHub.joinToString()), style, color)
                    peer.endpoint?.let {
                        StatText(stringResource(R.string.endpoint_template, it), style, color)
                        route?.let { r -> StatText(stringResource(R.string.route_template, r), style, color) }
                    }
                    StatText(
                        stringResource(
                            R.string.handshake_template,
                            handshake.toAgoDisplay(now) ?: stringResource(R.string.never),
                        ),
                        style,
                        color,
                    )
                    TransferStatsRow(
                        rx = Formatter.formatFileSize(context, peer.rxBytes ?: 0L),
                        tx = Formatter.formatFileSize(context, peer.txBytes ?: 0L),
                        style = style,
                        color = color,
                    )
                    StatText(
                        stringResource(
                            R.string.rtt_template,
                            rtt?.let { "${it.toInt()} ms" } ?: stringResource(R.string.ping_unreachable),
                        ),
                        style,
                        color,
                    )
                }
            },
            trailing =
                host?.let {
                    { modifier ->
                        IconButton(onClick = { clipboard.copy(it) }, modifier) {
                            Icon(
                                Icons.Outlined.ContentCopy,
                                contentDescription = stringResource(R.string.copy_ip),
                            )
                        }
                    }
                },
        )
    }
}
