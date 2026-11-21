package com.shazan.airbridge.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.shazan.airbridge.ui.theme.AbCard
import com.shazan.airbridge.ui.theme.Dimens
import com.shazan.airbridge.ui.theme.InfoRow

@Composable
internal fun SearchingCard(icon: ImageVector, title: String, body: String) {
    AbCard {
        InfoRow(
            title = title,
            subtitle = body,
            leading = {
                Box(
                    Modifier.size(Dimens.avatar).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(Dimens.iconSm))
                }
            },
            trailing = { CircularProgressIndicator(Modifier.size(Dimens.iconSm), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        )
    }
}
