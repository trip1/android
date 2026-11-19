package com.zaneschepke.wireguardautotunnel.ui.screens.tunnels.settings

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.core.orchestration.DirectPaths
import com.zaneschepke.wireguardautotunnel.data.dao.TravelSessionDao
import com.zaneschepke.wireguardautotunnel.ui.common.button.SurfaceRow
import com.zaneschepke.wireguardautotunnel.ui.common.dialog.InfoDialog
import com.zaneschepke.wireguardautotunnel.ui.common.label.GroupLabel
import com.zaneschepke.wireguardautotunnel.ui.common.text.DescriptionText
import com.zaneschepke.wireguardautotunnel.util.MeshMapLayout
import com.zaneschepke.wireguardautotunnel.util.Travel
import com.zaneschepke.wireguardautotunnel.util.Warp
import com.zaneschepke.wireguardautotunnel.util.network.Nebula
import com.zaneschepke.wireguardautotunnel.viewmodel.TunnelViewModel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.orbitmvi.orbit.compose.collectAsState

/** One map node; [via] = the hub/relay name a relayed node is reached through (null = direct or a hub itself). */
private data class MapNode(val name: String, val online: Boolean, val hub: Boolean, val via: String?, val trafficKey: String?)

/**
 * Live mesh map: hubs/lighthouses in the centre, peers on a ring, this device on top. Solid line = direct, dashed =
 * relayed (through the hub it points at), grey = offline; a dot runs along a line whose rx/tx moved since the last poll.
 */
@Composable
fun MeshMapScreen(viewModel: TunnelViewModel) {
    val uiState by viewModel.collectAsState()
    val tunnel = uiState.tunnel ?: return
    val hub = remember(tunnel) { DirectPaths.hubOf(tunnel) } ?: return
    val dir = DirectPaths.directories.collectAsState().value[tunnel.id]
    val direct = DirectPaths.direct.collectAsState().value[tunnel.id].orEmpty()
    val profile = remember(tunnel.nebulaProfile) { tunnel.nebulaProfile.takeIf { it.isNotEmpty() }?.let(Nebula::parse) }
    val peers = uiState.activeConfig?.peers.orEmpty()

    val lighthouses = profile?.lighthouses.orEmpty().associate { it.ip to it.name } + (hub to (profile?.lighthouses?.firstOrNull()?.name ?: "hub"))
    // traffic key per peer = publicKey (WireGuard) / cert name (Nebula); counters summed
    val traffic = peers.associate { it.publicKey to (it.rxBytes ?: 0L) + (it.txBytes ?: 0L) }
    val hubPeerKey = if (profile == null) peers.firstOrNull { it.publicKey !in direct }?.publicKey else null
    // Nebula hosts are keyed by cert name (= directory name); the directory may list only the WG-side address
    fun peerOf(addrs: List<String>, name: String? = null) = peers.firstOrNull { p -> p.allowedIPs.orEmpty().split(',').any { it.trim().substringBefore('/') in addrs } }
        ?: peers.firstOrNull { it.publicKey == name }

    val devices = dir?.devices.orEmpty().filter { it.meshIp != dir?.selfIp }
    val hubs = devices.filter { d -> d.kind == "node" || d.addresses.any { it in lighthouses } }
    val hubNodes = hubs.map { d ->
        MapNode(d.name, d.online, true, null, if (profile != null) peerOf(d.addresses, d.name)?.publicKey else hubPeerKey)
    }.ifEmpty { listOf(MapNode(lighthouses.getValue(hub), true, true, null, hubPeerKey)) }
    val names = devices.associate { it.meshIp to it.name } + lighthouses
    val ring = devices.filter { it !in hubs }.map { d ->
        val p = if (profile != null) peerOf(d.addresses, d.name) else null
        val isDirect = if (profile != null) p?.endpoint?.let { !it.startsWith("relay ") } == true else d.publicKey in direct
        val via = when {
            isDirect -> null
            profile != null -> p?.endpoint?.removePrefix("relay ")?.let { names[it] ?: it } ?: hubNodes.first().name
            else -> hubNodes.first().name
        }
        MapNode(d.name, d.online, false, via, if (isDirect) d.publicKey.takeIf { profile == null } ?: p?.publicKey else p?.publicKey)
    }

    // pulse = counters moved since the previous poll
    var last by remember { mutableStateOf(emptyMap<String, Long>()) }
    var moving by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(traffic) {
        moving = traffic.filter { (k, v) -> last[k]?.let { it != v } == true }.keys
        last = traffic
    }

    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.outline // visible on every theme (secondary is a dark panel colour on Dark)
    val fg = MaterialTheme.colorScheme.onSurface
    val off = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val measurer = rememberTextMeasurer()
    val selfName = stringResource(R.string.map_this_device)
    val all = remember(selfName, ring) { listOf(MapNode(selfName, true, false, null, null)) + ring }
    // pre-measure node labels outside Canvas so animation frames don't re-layout text 60-120x/s
    val allNodes = remember(hubNodes, all) { hubNodes + all }
    val labels = remember(allNodes, fg, off) {
        allNodes.map { n -> measurer.measure(n.name, TextStyle(color = if (n.online) fg else off, fontSize = 11.sp)) }
    }
    val pos = remember(hubNodes.size, all.size) { MeshMapLayout.positions(hubNodes.size, all.size) }
    val dash = remember { PathEffect.dashPathEffect(floatArrayOf(14f, 10f)) }
    val pulse = if (moving.isNotEmpty()) {
        rememberInfiniteTransition(label = "pulse").animateFloat(
            0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "pulse"
        ).value
    } else 0f

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (dir == null) DescriptionText(stringResource(R.string.mesh_waiting), Modifier.padding(horizontal = 16.dp))
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f).padding(16.dp)) {
            val r = size.minDimension / 2
            fun at(i: Int) = pos[i].let { (x, y) -> Offset(center.x + x * r * 0.85f, center.y + y * r * 0.85f) }
            val hubAt = hubNodes.indices.associate { hubNodes[it].name to at(it) }
            val selfAt = at(hubNodes.size)
            fun line(a: Offset, b: Offset, color: Color, dashed: Boolean, active: Boolean) {
                drawLine(color, a, b, strokeWidth = 4f, pathEffect = if (dashed) dash else null, cap = StrokeCap.Round)
                if (active) drawCircle(color, 9f, a + (b - a) * pulse)
            }
            // this device -> every hub (the tunnel always reaches its hubs)
            hubNodes.forEach { h -> line(selfAt, hubAt.getValue(h.name), primary, false, h.trafficKey in moving) }
            all.forEachIndexed { i, n ->
                if (i == 0) return@forEachIndexed
                val p = at(hubNodes.size + i)
                when {
                    !n.online -> {}
                    n.via == null -> line(selfAt, p, primary, false, n.trafficKey in moving)
                    else -> line(hubAt[n.via] ?: hubAt.values.first(), p, secondary, true, n.trafficKey in moving)
                }
            }
            allNodes.forEachIndexed { i, n ->
                val p = at(i)
                val c = when { !n.online -> off; n.hub -> secondary; n.name == selfName -> primary; else -> fg }
                drawCircle(c, if (n.hub) 22f else 15f, p)
                val t = labels[i]
                drawText(t, topLeft = Offset(p.x - t.size.width / 2, p.y + 20f))
            }
        }
        DescriptionText(stringResource(R.string.map_legend), Modifier.padding(horizontal = 16.dp))
    }
}

/** Warp factor gauge: Mbit/s mapped by [Warp.factor], needle springs to the new value. */
@Composable
fun WarpGauge(mbps: Double?, modifier: Modifier = Modifier) {
    val warp = mbps?.let(Warp::factor) ?: 1.0
    val shown by animateFloatAsState(warp.toFloat(), spring(dampingRatio = 0.45f, stiffness = 40f), label = "warp")
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val fg = MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().height(170.dp).padding(horizontal = 16.dp)) {
        val r = minOf(size.width / 2, size.height * 0.75f) * 0.95f
        val c = Offset(size.width / 2, size.height * 0.95f)
        val topLeft = Offset(c.x - r, c.y - r)
        val arc = androidx.compose.ui.geometry.Size(2 * r, 2 * r)
        drawArc(track, 180f, 180f, false, topLeft, arc, style = Stroke(18f, cap = StrokeCap.Round))
        val frac = ((shown - 1f) / 8.99f).coerceIn(0f, 1f)
        drawArc(primary, 180f, 180f * frac, false, topLeft, arc, style = Stroke(18f, cap = StrokeCap.Round))
        rotate(180f * frac - 90f, c) { drawLine(fg, c, Offset(c.x, c.y - r * 0.8f), strokeWidth = 6f, cap = StrokeCap.Round) }
        drawCircle(primary, 12f, c)
        val t = measurer.measure("WARP %.2f".format(shown), TextStyle(color = fg, fontSize = 18.sp))
        drawText(t, topLeft = Offset(c.x - t.size.width / 2, 0f)) // above the arc, clear of the needle
    }
}

/** Travel log: totals, achievements and every session; nothing leaves the device. */
@Composable
fun TravelLogScreen(dao: TravelSessionDao = koinInject()) {
    val context = LocalContext.current
    val sessions by dao.all().collectAsState(initial = emptyList())
    val stats = remember(sessions) { Travel.stats(sessions) }
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    val labels = stringResource(R.string.travel_achievement_names).split('|')
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "totals") {
            Column {
                GroupLabel(stringResource(R.string.travel_totals), modifier = Modifier.padding(horizontal = 16.dp))
                SurfaceRow(title = stringResource(R.string.travel_totals_line, Formatter.formatFileSize(context, stats.bytes),
                    stats.hours, stats.countries.size, stats.longestStreakDays),
                    description = { DescriptionText(stats.countries.sorted().joinToString(" ").ifEmpty { stringResource(R.string.travel_no_country) }) })
            }
        }
        item(key = "achievements") {
            Column {
                GroupLabel(stringResource(R.string.travel_achievements), modifier = Modifier.padding(horizontal = 16.dp))
                Travel.Achievement.entries.forEachIndexed { i, a ->
                    val on = a.unlocked(stats)
                    SurfaceRow(
                        leading = { Icon(if (on) Icons.Outlined.EmojiEvents else Icons.Outlined.Lock, contentDescription = null,
                            tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) },
                        title = labels.getOrElse(i) { a.name },
                        description = { DescriptionText(stringResource(if (on) R.string.travel_unlocked else R.string.travel_locked), disabled = !on) },
                    )
                }
            }
        }
        item(key = "sessions_header") {
            Column {
                GroupLabel(stringResource(R.string.travel_sessions), modifier = Modifier.padding(horizontal = 16.dp))
                if (sessions.isEmpty()) DescriptionText(stringResource(R.string.travel_empty), Modifier.padding(horizontal = 16.dp))
            }
        }
        items(sessions, key = { it.id }) { s ->
            SurfaceRow(
                title = "${s.tunnelName} · ${DateUtils.formatDateTime(context, s.startMs, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME)}",
                description = {
                    DescriptionText(listOfNotNull(
                        DateUtils.formatElapsedTime((s.endMs - s.startMs).coerceAtLeast(0) / 1000), s.network, s.ssid, s.country,
                        "↓" + Formatter.formatFileSize(context, s.rxBytes) + " ↑" + Formatter.formatFileSize(context, s.txBytes),
                        stringResource(R.string.travel_relayed).takeIf { s.relayed },
                    ).joinToString(" · "))
                },
            )
        }
        item(key = "footer") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SurfaceRow(leading = { Icon(Icons.Outlined.DeleteSweep, contentDescription = null) },
                    title = stringResource(R.string.travel_clear), onClick = { confirm = true })
                Text(stringResource(R.string.travel_private), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline)
            }
        }
    }
    if (confirm) InfoDialog(onAttest = { confirm = false; scope.launch { dao.clear() } }, onDismiss = { confirm = false },
        title = stringResource(R.string.travel_clear), body = { Text(stringResource(R.string.travel_clear_confirm)) },
        confirmText = stringResource(R.string.travel_clear))
}

