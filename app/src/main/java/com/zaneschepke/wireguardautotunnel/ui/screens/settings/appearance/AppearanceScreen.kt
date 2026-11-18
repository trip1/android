package com.zaneschepke.wireguardautotunnel.ui.screens.settings.appearance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import com.zaneschepke.wireguardautotunnel.data.DataStoreManager
import com.zaneschepke.wireguardautotunnel.ui.common.button.ThemedSwitch
import com.zaneschepke.wireguardautotunnel.ui.common.text.DescriptionText
import com.zaneschepke.wireguardautotunnel.util.UiSounds
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zaneschepke.wireguardautotunnel.R
import com.zaneschepke.wireguardautotunnel.ui.LocalNavController
import com.zaneschepke.wireguardautotunnel.ui.common.button.SurfaceRow
import com.zaneschepke.wireguardautotunnel.ui.common.label.GroupLabel
import com.zaneschepke.wireguardautotunnel.ui.navigation.Route

@Composable
fun AppearanceScreen() {
    val navController = LocalNavController.current

    Column(
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Top),
        modifier = Modifier.fillMaxSize(),
    ) {
        Column {
            GroupLabel(
                stringResource(R.string.display),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            SurfaceRow(
                leading = { Icon(Icons.Outlined.Translate, contentDescription = null) },
                title = stringResource(R.string.language),
                onClick = { navController.push(Route.Language) },
            )
            SurfaceRow(
                leading = { Icon(Icons.Outlined.Contrast, contentDescription = null) },
                title = stringResource(R.string.display_theme),
                onClick = { navController.push(Route.Display) },
            )
            SurfaceRow(
                leading = { Icon(Icons.Outlined.Notifications, contentDescription = null) },
                title = stringResource(R.string.notifications),
                onClick = { navController.push(Route.Notifications) },
            )
        }
        Column {
            GroupLabel(stringResource(R.string.starship), modifier = Modifier.padding(horizontal = 16.dp))
            val dataStore: DataStoreManager = koinInject()
            val scope = rememberCoroutineScope()
            val sounds by dataStore.getFromStoreFlow(UiSounds.KEY).collectAsState(initial = false)
            val toggle = { on: Boolean -> scope.launch { dataStore.saveToDataStore(UiSounds.KEY, on) }; Unit }
            SurfaceRow(
                leading = { Icon(Icons.Outlined.MusicNote, contentDescription = null) },
                title = stringResource(R.string.ui_sounds),
                description = { DescriptionText(stringResource(R.string.ui_sounds_desc)) },
                trailing = { ThemedSwitch(sounds == true, onClick = toggle) },
                onClick = { toggle(sounds != true) },
            )
            SurfaceRow(
                leading = { Icon(Icons.Outlined.FlightTakeoff, contentDescription = null) },
                title = stringResource(R.string.travel_title),
                description = { DescriptionText(stringResource(R.string.travel_desc)) },
                onClick = { navController.push(Route.TravelLog) },
            )
        }
    }
}
