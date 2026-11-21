package com.shazan.airbridge.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shazan.airbridge.R
import com.shazan.airbridge.connection.DisconnectBy
import com.shazan.airbridge.connection.PeerStatus
import com.shazan.airbridge.connection.pairing.PairedPeer
import com.shazan.airbridge.control.trackpad.TrackpadActivity
import com.shazan.airbridge.ui.Screen
import com.shazan.airbridge.ui.theme.AbCard
import com.shazan.airbridge.ui.theme.AbTheme
import com.shazan.airbridge.ui.theme.ChipTone
import com.shazan.airbridge.ui.theme.Dimens
import com.shazan.airbridge.ui.theme.PlatformAvatar
import com.shazan.airbridge.ui.theme.StatusChip
import com.shazan.airbridge.ui.theme.platformLabel

@Composable
internal fun DeviceCard(
    peer: PairedPeer,
    caps: Set<String>,
    status: PeerStatus,
    controllingYou: Boolean,
    onForget: () -> Unit,
    onTrackpad: (TrackpadActivity.Mode) -> Unit,
    onDisconnect: () -> Unit,
    onConnect: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val disconnected = status == PeerStatus.DISCONNECTED
    val (label, tone) = when {
        controllingYou -> stringResource(R.string.st_controlling_you) to ChipTone.INFO
        status == PeerStatus.CONNECTED -> stringResource(R.string.st_connected) to ChipTone.SUCCESS
        status == PeerStatus.CONNECTING -> stringResource(R.string.st_connecting) to ChipTone.WARNING
        status == PeerStatus.RECONNECTING -> stringResource(R.string.st_reconnecting) to ChipTone.WARNING
        disconnected && peer.disconnectedBy == DisconnectBy.PEER -> stringResource(R.string.st_disconnected_by, peer.name) to ChipTone.NEUTRAL
        disconnected -> stringResource(R.string.st_disconnected) to ChipTone.NEUTRAL
        else -> stringResource(R.string.st_offline) to ChipTone.NEUTRAL
    }
    val desktop = peer.plat == "macos" || peer.plat == "windows" || peer.plat == "linux"
    // Every device gets the same choices, whichever way it is reached (this phone dials it, it dials this
    // phone, or Nearby): a menu with only "Forget" looked broken.
    val canDisconnect = status == PeerStatus.CONNECTED || status == PeerStatus.CONNECTING || status == PeerStatus.RECONNECTING
    val canConnect = disconnected || status == PeerStatus.OFFLINE
    AbCard {
        Row(
            Modifier.fillMaxWidth().padding(start = Dimens.md, top = Dimens.md, bottom = Dimens.md, end = Dimens.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlatformAvatar(peer.plat)
            Spacer(Modifier.width(Dimens.md))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.xs)) {
                Text(peer.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${platformLabel(peer.plat)} · ${if (peer.via == "nearby") "Nearby" else "Wi-Fi"}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(label, tone, modifier = Modifier.weight(1f, fill = false))
                    if (disconnected) {
                        Spacer(Modifier.width(Dimens.xs))
                        TextButton(onClick = onConnect, modifier = Modifier.height(Dimens.touch)) { Text(stringResource(R.string.connect)) }
                    }
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.more_options_for, peer.name)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if ((desktop || (peer.plat == "android" && com.shazan.airbridge.connection.session.Protocol.CAP_TARGET in caps)) && status == PeerStatus.CONNECTED) DropdownMenuItem(
                        text = { Text(stringResource(R.string.use_as_trackpad)) },
                        onClick = { menu = false; onTrackpad(TrackpadActivity.Mode.Trackpad) },
                    )
                    if (canDisconnect) DropdownMenuItem(
                        text = { Text(stringResource(R.string.disconnect)) },
                        onClick = { menu = false; onDisconnect() },
                    )
                    if (canConnect) DropdownMenuItem(
                        text = { Text(stringResource(R.string.connect)) },
                        onClick = { menu = false; onConnect() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.forget_device), color = AbTheme.status.danger) },
                        onClick = { menu = false; onForget() },
                    )
                }
            }
        }
        // A connected computer or phone: the ways to control it, one tap away. A phone is controllable
        // once its AirBridge Universal Control service is on (it then advertises ctl_target); Screen mode
        // needs it to advertise mirror_source as well (its user then allows each session there).
        val phone = peer.plat == "android"
        val drivable = desktop || (phone && com.shazan.airbridge.connection.session.Protocol.CAP_TARGET in caps)
        if (status == PeerStatus.CONNECTED && !controllingYou && drivable) {
            Row(
                Modifier.fillMaxWidth().padding(start = Dimens.md, end = Dimens.md, bottom = Dimens.md),
                horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
            ) {
                for (m in TrackpadActivity.Mode.entries) {
                    if (phone && m == TrackpadActivity.Mode.Screen && "mirror_source" !in caps) continue
                    CardAction(stringResource(m.labelRes), m.icon, Modifier.weight(1f)) { onTrackpad(m) }
                }
            }
        } else if (status == PeerStatus.CONNECTED && phone && !controllingYou) {
            Text(
                stringResource(R.string.to_control_phone, peer.name),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Dimens.md, end = Dimens.md, bottom = Dimens.md),
            )
        }
    }
}
/** Compact tonal action inside a device card: icon over a short label, 56 dp tall. */
@Composable
private fun CardAction(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.height(56.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(Dimens.iconSm))
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                autoSize = com.shazan.airbridge.ui.theme.fitOneLine(MaterialTheme.typography.labelMedium, 9f))
        }
    }
}
