package com.zaneschepke.wireguardautotunnel.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Travel log: one row per tunnel session, local only. [ssid] only when location permission was already granted. */
@Entity(tableName = "travel_session")
data class TravelSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "start_ms") val startMs: Long,
    @ColumnInfo(name = "end_ms") val endMs: Long,
    @ColumnInfo(name = "tunnel_name") val tunnelName: String,
    @ColumnInfo(name = "network") val network: String,
    @ColumnInfo(name = "ssid") val ssid: String? = null,
    @ColumnInfo(name = "country") val country: String? = null,
    @ColumnInfo(name = "rx_bytes", defaultValue = "0") val rxBytes: Long = 0,
    @ColumnInfo(name = "tx_bytes", defaultValue = "0") val txBytes: Long = 0,
    @ColumnInfo(name = "relayed", defaultValue = "0") val relayed: Boolean = false,
)
