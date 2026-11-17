package com.zaneschepke.wireguardautotunnel.domain.enums

import android.content.Context
import com.zaneschepke.wireguardautotunnel.R

enum class NotificationAction {
    TUNNEL_OFF,
    AUTO_TUNNEL_OFF,
    STOP_ALL,
    TOGGLE; // home-screen widget

    fun title(context: Context): String {
        return when (this) {
            TUNNEL_OFF -> context.getString(R.string.stop)
            AUTO_TUNNEL_OFF -> context.getString(R.string.stop)
            STOP_ALL -> context.getString(R.string.stop_all)
            TOGGLE -> context.getString(R.string.widget_turn_on)
        }
    }
}
