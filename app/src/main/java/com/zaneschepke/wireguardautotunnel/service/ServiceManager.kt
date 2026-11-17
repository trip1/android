package com.zaneschepke.wireguardautotunnel.service

import android.content.Context
import android.content.Intent
import android.net.VpnService
import chat.warpsignal.nebmobile.Nebmobile
import com.zaneschepke.wireguardautotunnel.util.network.Nebula
import com.zaneschepke.wireguardautotunnel.service.autotunnel.AutoTunnelService
import com.zaneschepke.wireguardautotunnel.util.permission.NotificationPermissionHelper

class ServiceManager(private val context: Context) {

    fun startAutoTunnelService() {
        context.startForegroundService(Intent(context, AutoTunnelService::class.java))
    }

    fun stopAutoTunnelService() {
        context.stopService(Intent(context, AutoTunnelService::class.java))
    }

    suspend fun startNebula(id: Int, name: String, profileJson: String, yaml: String, included: List<String>, excluded: List<String>) =
        NebulaVpnService.start(context, id, name, profileJson, yaml, included, excluded)

    fun stopNebula() = NebulaVpnService.stop(context)

    fun rebindNebula() {
        if (NebulaVpnService.active.value != null) Nebula.call { Nebmobile.rebind() }
    }

    fun hasVpnPermission(): Boolean {
        return VpnService.prepare(context) == null
    }

    fun hasNotificationPermission(): Boolean {
        return NotificationPermissionHelper.isPermissionGranted(context)
    }
}
