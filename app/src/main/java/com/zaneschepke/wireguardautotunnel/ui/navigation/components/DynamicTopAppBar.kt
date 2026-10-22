package com.zaneschepke.wireguardautotunnel.ui.navigation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zaneschepke.wireguardautotunnel.ui.state.NavbarState
import com.zaneschepke.wireguardautotunnel.ui.theme.LcarsAmber
import com.zaneschepke.wireguardautotunnel.ui.theme.LcarsBlue
import com.zaneschepke.wireguardautotunnel.ui.theme.LcarsGold
import com.zaneschepke.wireguardautotunnel.ui.theme.LcarsPeriwinkle
import com.zaneschepke.wireguardautotunnel.ui.theme.LockedDownBannerHeight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DynamicTopAppBar(navBarState: NavbarState, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(top = LockedDownBannerHeight)) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors().copy(containerColor = Color.Transparent),
            navigationIcon = { navBarState.topLeading?.invoke() },
            title = {
                navBarState.topTitle?.let {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier =
                                Modifier.width(18.dp)
                                    .height(22.dp)
                                    .clip(
                                        RoundedCornerShape(
                                            topStart = 12.dp,
                                            bottomStart = 12.dp,
                                            topEnd = 2.dp,
                                            bottomEnd = 2.dp,
                                        )
                                    )
                                    .background(LcarsAmber)
                        )
                        Column {
                            Text(
                                text = "LCARS // SUBSPACE CONDUIT",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.6.sp,
                                color = LcarsBlue,
                            )
                            Text(
                                text = it.uppercase(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.2.sp,
                                color = LcarsGold,
                            )
                        }
                    }
                }
            },
            actions = { navBarState.topTrailing?.invoke() },
        )
        // LCARS Tactical Segmented Swept Rail
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier.width(56.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                        .background(LcarsAmber)
            )
            Box(modifier = Modifier.weight(1f).height(6.dp).background(LcarsGold))
            Box(modifier = Modifier.width(36.dp).height(6.dp).background(LcarsPeriwinkle))
            Box(
                modifier =
                    Modifier.width(24.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                        .background(LcarsBlue)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}
