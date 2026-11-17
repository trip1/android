package com.zaneschepke.wireguardautotunnel.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.zaneschepke.wireguardautotunnel.MainActivity
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.core.orchestration.DirectPaths
import com.zaneschepke.wireguardautotunnel.core.orchestration.TunnelCoordinator
import com.zaneschepke.wireguardautotunnel.core.broadcast.NotificationActionReceiver
import com.zaneschepke.wireguardautotunnel.domain.enums.NotificationAction
import com.zaneschepke.wireguardautotunnel.domain.repository.TunnelRepository
import com.zaneschepke.wireguardautotunnel.util.network.Nebula
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Home-screen widget (RemoteViews, no Glance dependency): on/off toggle, tunnel + exit, direct/relayed paths, online
 * peers. Redrawn on every tunnel/directory change while the app process lives ([bind]) and by the system every 30 min.
 */
class TunnelWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = redraw(context)

    data class Summary(val title: String, val exit: String, val paths: String, val online: String, val on: Boolean)

    companion object : KoinComponent {
        @Volatile private var last: Summary? = null

        /** Keeps placed widgets current; called once from Application.onCreate. */
        fun bind(context: Context, scope: CoroutineScope) {
            val coordinator: TunnelCoordinator by inject()
            val repo: TunnelRepository by inject()
            scope.launch {
                combine(coordinator.backendStatus, DirectPaths.directories, DirectPaths.direct, repo.userTunnelsFlow) { st, dirs, direct, tunnels ->
                    val id = st.activeTunnels.keys.firstOrNull()
                    val t = tunnels.firstOrNull { it.id == id }
                    if (id == null || t == null) return@combine Summary(context.getString(R.string.widget_off), "", "", "", false)
                    val nebula = t.nebulaProfile.takeIf { it.isNotEmpty() }?.let(Nebula::parse)
                    val exit = when {
                        !t.isExitNode -> context.getString(R.string.widget_no_exit)
                        nebula != null -> nebula.exits.firstOrNull { it.ip == (nebula.exitIp ?: nebula.exits.firstOrNull()?.ip) }?.name ?: "hub"
                        else -> "hub"
                    }
                    val peers = st.activeTunnels[id]?.activeConfig?.peers.orEmpty()
                    val relayed = if (nebula != null) peers.count { it.endpoint?.startsWith("relay ") == true } else 0
                    val directN = if (nebula != null) peers.count { it.endpoint != null } - relayed else direct[id].orEmpty().size
                    val dir = dirs[id]
                    val online = dir?.devices?.count { it.online && it.meshIp != dir.selfIp } ?: peers.size
                    Summary(t.name, context.getString(R.string.widget_exit, exit),
                        context.getString(R.string.widget_paths, directN, relayed),
                        context.getString(R.string.widget_online, online), true)
                }.distinctUntilChanged().collect { last = it; redraw(context) }
            }
        }

        fun redraw(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TunnelWidget::class.java))
            if (ids.isEmpty()) return
            val s = last ?: Summary(context.getString(R.string.widget_off), "", "", "", false)
            val views = RemoteViews(context.packageName, R.layout.widget_tunnel).apply {
                setTextViewText(R.id.widget_title, s.title)
                setTextViewText(R.id.widget_exit, s.exit)
                setTextViewText(R.id.widget_paths, s.paths)
                setTextViewText(R.id.widget_online, s.online)
                setTextViewText(R.id.widget_toggle, context.getString(if (s.on) R.string.widget_turn_off else R.string.widget_turn_on))
                setOnClickPendingIntent(R.id.widget_toggle, PendingIntent.getBroadcast(context, 0,
                    // the widget provider is exported (launcher broadcasts); the toggle goes to the guarded receiver
                    Intent(context, NotificationActionReceiver::class.java).setAction(NotificationAction.TOGGLE.name),
                    PendingIntent.FLAG_IMMUTABLE))
                setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 0,
                    Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            }
            manager.updateAppWidget(ids, views)
        }
    }
}
