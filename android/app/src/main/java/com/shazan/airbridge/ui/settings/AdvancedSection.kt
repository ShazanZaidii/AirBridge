package com.shazan.airbridge.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.shazan.airbridge.R
import com.shazan.airbridge.ui.theme.AbCard
import com.shazan.airbridge.ui.theme.Dimens
import com.shazan.airbridge.ui.theme.InfoRow
import com.shazan.airbridge.ui.theme.RowDivider

/** Tips for phones that close apps in the background, folded away until asked for. Nothing here is needed to use AirBridge. */
@Composable
internal fun AdvancedSection() {
    var open by rememberSaveable { mutableStateOf(false) }
    Spacer(Modifier.height(Dimens.lg))
    AbCard {
        InfoRow(
            title = stringResource(R.string.adv_title), subtitle = stringResource(R.string.adv_sub),
            modifier = Modifier.clickable { open = !open },
            trailing = { Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        )
        if (open) {
            RowDivider()
            KeepAliveHint()
        }
    }
}
