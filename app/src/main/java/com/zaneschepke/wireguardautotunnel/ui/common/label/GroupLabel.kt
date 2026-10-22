package com.zaneschepke.wireguardautotunnel.ui.common.label

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zaneschepke.wireguardautotunnel.ui.theme.LcarsBlue

@Composable
fun GroupLabel(
    title: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            modifier =
                Modifier.width(16.dp)
                    .height(12.dp)
                    .clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
                    .background(color)
        )
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.4.sp,
            color = color,
        )
        Box(
            modifier =
                Modifier.weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                    .background(LcarsBlue.copy(alpha = 0.45f))
        )
    }
}
