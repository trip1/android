package com.zaneschepke.wireguardautotunnel.ui.screens.tunnels.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.ui.common.dialog.InfoDialog
import com.zaneschepke.wireguardautotunnel.ui.common.textbox.ConfigurationTextBox
import com.zaneschepke.wireguardautotunnel.util.network.MeshEnroll

@Composable
fun EnrollDialog(onDismiss: () -> Unit, onConfirm: (MeshEnroll.Code) -> Unit) {
    var code by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }

    InfoDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.enroll_with_code),
        body = {
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Text(
                    stringResource(R.string.enroll_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                ConfigurationTextBox(
                    value = code,
                    label = stringResource(R.string.enroll_code),
                    hint = "transwarp://enroll?u=…&t=…&ca=…",
                    onValueChange = {
                        code = it
                        isError = false
                    },
                    isError = isError,
                )
            }
        },
        confirmText = stringResource(R.string.okay),
        onAttest = { MeshEnroll.parseCode(code)?.let(onConfirm) ?: run { isError = true } },
    )
}
