package com.zaneschepke.wireguardautotunnel.ui.screens.tunnels.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import chat.warpsignal.nebmobile.Nebmobile
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.ui.common.button.SurfaceRow
import com.zaneschepke.wireguardautotunnel.ui.common.label.GroupLabel
import com.zaneschepke.wireguardautotunnel.ui.common.text.DescriptionText
import com.zaneschepke.wireguardautotunnel.ui.common.textbox.ConfigurationTextBox
import com.zaneschepke.wireguardautotunnel.ui.theme.LcarsCrimson
import com.zaneschepke.wireguardautotunnel.util.network.Nebula
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Nebula tunnel settings: status (when running), role picker, listen port, read-only lighthouse list. */
@Composable
fun NebulaSettings(profileJson: String, isRunning: Boolean, onChange: (role: String, listenPort: Int) -> Unit) {
    val p = remember(profileJson) { Nebula.parse(profileJson) }
    if (isRunning) {
        val status by
            produceState<JsonObject?>(null, profileJson) {
                while (true) {
                    value = withContext(Nebula.dispatcher) {
                        runCatching { Json.parseToJsonElement(Nebmobile.status()).jsonObject }.getOrNull()
                    }
                    delay(3.seconds)
                }
            }
        Column {
            GroupLabel(stringResource(R.string.nebula_status), modifier = Modifier.padding(horizontal = 16.dp))
            val s = status
            val lighthouses = s?.get("lighthouses")?.jsonArray.orEmpty().map { it.jsonObject }
            val hosts = s?.get("hosts")?.jsonArray.orEmpty().map { it.jsonObject }
            val up = lighthouses.count { it["reachable"]?.jsonPrimitive?.boolean == true }
            SurfaceRow(
                leading = { Icon(Icons.Outlined.Sensors, contentDescription = null) },
                title = stringResource(R.string.nebula_ip, p.ip),
                description = {
                    Column {
                        DescriptionText(stringResource(R.string.nebula_lighthouses_reachable, up, lighthouses.size))
                        if (s?.get("am_lighthouse")?.jsonPrimitive?.boolean == true) {
                            DescriptionText(
                                stringResource(R.string.nebula_serving_lighthouse, s["listen_port"]?.jsonPrimitive?.content.orEmpty())
                            )
                        }
                        hosts.forEach { h ->
                            val ip = h["vpn_addrs"]?.jsonArray?.joinToString { it.jsonPrimitive.content }.orEmpty()
                            val via = h["remote"]?.jsonPrimitive?.content
                                ?: h["relays"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content?.let { "relay $it" }
                                ?: "?"
                            DescriptionText("$ip ← $via")
                        }
                    }
                },
            )
        }
    }
    Column {
        GroupLabel(stringResource(R.string.nebula_role), modifier = Modifier.padding(horizontal = 16.dp))
        listOf(
                Nebula.ROLE_CLIENT to R.string.nebula_role_client,
                Nebula.ROLE_LIGHTHOUSE to R.string.nebula_role_lighthouse,
                Nebula.ROLE_BOTH to R.string.nebula_role_both,
            )
            .forEach { (role, label) ->
                SurfaceRow(
                    leading = { Icon(Icons.Outlined.Hub, contentDescription = null) },
                    title = stringResource(label),
                    trailing = {
                        if (p.role == role) {
                            Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    onClick = { onChange(role, 0) },
                )
            }
        if (p.isLighthouse) {
            Text(
                stringResource(R.string.nebula_lighthouse_warning),
                style = MaterialTheme.typography.bodySmall,
                color = LcarsCrimson,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            var port by remember(p.listenPort) { mutableStateOf(p.listenPort.toString()) }
            val valid = port.toIntOrNull() in 1..65535
            ConfigurationTextBox(
                value = port,
                label = stringResource(R.string.nebula_listen_port),
                hint = "4242",
                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                isError = !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onChange("", port.toInt()) }),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
    Column {
        GroupLabel(stringResource(R.string.nebula_lighthouses), modifier = Modifier.padding(horizontal = 16.dp))
        p.lighthouses.forEach { lh ->
            SurfaceRow(
                leading = { Icon(Icons.Outlined.Lan, contentDescription = null) },
                title = "${lh.name.ifEmpty { lh.ip }} · ${lh.ip}",
                description = { DescriptionText(lh.endpoints.joinToString()) },
            )
        }
    }
}
