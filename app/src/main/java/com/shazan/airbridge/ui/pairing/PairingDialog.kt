package com.shazan.airbridge.ui.pairing

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shazan.airbridge.R
import com.shazan.airbridge.connection.PairProgress
import com.shazan.airbridge.connection.PairStage
import com.shazan.airbridge.ui.theme.Dimens
import com.shazan.airbridge.ui.theme.platformNoun

/** "483920" → "483 920", read digit by digit by screen readers. */
@Composable
internal fun DigitsText(digits: String, modifier: Modifier = Modifier) {
    val codeLabel = stringResource(R.string.code_label)
    val shown = if (digits.length == 6) digits.substring(0, 3) + " " + digits.substring(3) else digits
    Text(
        shown,
        style = MaterialTheme.typography.displayMedium,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        maxLines = 1, // the code must never wrap: shrink to fit narrow screens and big font settings
        autoSize = com.shazan.airbridge.ui.theme.fitOneLine(MaterialTheme.typography.displayMedium, 22f),
        modifier = modifier.fillMaxWidth().semantics { contentDescription = codeLabel + " " + digits.toCharArray().joinToString(" ") },
    )
}
/** The LAN pairing sheet: connecting → compare digits → waiting for the computer → result (§3.3). */
@Composable
internal fun PairingDialog(p: PairProgress, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val noun = platformNoun(p.plat)
    val atFmt = stringResource(R.string.at_address)
    val atAddress = { a: String -> atFmt.format(a) }
    AlertDialog(
        onDismissRequest = {}, // explicit Cancel only, so a stray tap never aborts a pairing
        title = {
            Text(
                when (p.stage) {
                    PairStage.CONNECTING -> stringResource(R.string.connecting_to, p.peerName)
                    PairStage.CONFIRM -> stringResource(R.string.pair_with_q, p.peerName)
                    PairStage.WAITING_PEER -> stringResource(R.string.confirm_on_your, noun)
                }
            )
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                when (p.stage) {
                    PairStage.CONNECTING -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(Dimens.iconMd), strokeWidth = 3.dp)
                        Spacer(Modifier.width(Dimens.md))
                        Text(if (p.mode == "qr") stringResource(R.string.checking_qr) else stringResource(R.string.securing),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    PairStage.CONFIRM -> {
                        // Who is this, exactly: kind of device and where it is.
                        Text(
                            listOfNotNull(com.shazan.airbridge.connection.deviceKind(p.plat), p.address?.let { atAddress(it) }).joinToString(" "),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(Dimens.sm))
                        Text(
                            if (p.incoming) stringResource(R.string.incoming_pair_body)
                            else stringResource(R.string.same_code_your, noun),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Dimens.lg))
                        DigitsText(p.digits ?: "")
                        Spacer(Modifier.height(Dimens.lg))
                        Text(stringResource(R.string.codes_differ), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    PairStage.WAITING_PEER -> {
                        DigitsText(p.digits ?: "")
                        Spacer(Modifier.height(Dimens.lg))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(Dimens.iconSm), strokeWidth = 2.dp)
                            Spacer(Modifier.width(Dimens.md))
                            Text(stringResource(R.string.accept_on, p.peerName), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { if (p.stage == PairStage.CONFIRM) TextButton(onClick = onConfirm) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}
