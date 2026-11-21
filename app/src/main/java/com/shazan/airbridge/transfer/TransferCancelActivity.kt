package com.shazan.airbridge.transfer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.shazan.airbridge.R
import com.shazan.airbridge.connection.ConnectionHub
import com.shazan.airbridge.ui.theme.AbTheme
import com.shazan.airbridge.ui.theme.AirBridgeTheme

/**
 * "Cancel receiving <file>?" before a running transfer is cancelled. Opened by the Cancel button of the transfer
 * notification, so one stray tap in the shade can't throw away a long transfer; confirming cancels at once (the
 * notification switches to "Cancelling…" straight away) and "Keep going" leaves everything as it was.
 */
class TransferCancelActivity : ComponentActivity() {
    private var peer = ""
    private var id = -1
    private var incoming = false
    private var file = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        read(intent)
        setContent {
            AirBridgeTheme {
                Ask()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        read(intent)
    }

    private fun read(i: Intent?) {
        peer = i?.getStringExtra(EXTRA_PEER).orEmpty()
        id = i?.getIntExtra(EXTRA_ID, -1) ?: -1
        incoming = i?.getBooleanExtra(EXTRA_INCOMING, false) ?: false
        file = i?.getStringExtra(EXTRA_FILE).orEmpty()
    }

    @Composable
    private fun Ask() {
        AlertDialog(
            onDismissRequest = { finish() },
            title = { Text(stringResource(if (incoming) R.string.xfer_cancel_recv_q else R.string.xfer_cancel_send_q, file)) },
            confirmButton = {
                TextButton(onClick = {
                    ConnectionHub.readySession(peer)?.xfer?.cancelLocal(id, incoming)
                    finish()
                }) { Text(stringResource(R.string.xfer_cancel_yes), color = AbTheme.status.danger) }
            },
            dismissButton = { TextButton(onClick = { finish() }) { Text(stringResource(R.string.xfer_cancel_keep)) } },
        )
    }

    companion object {
        const val EXTRA_PEER = "peer"
        const val EXTRA_ID = "id"
        const val EXTRA_INCOMING = "incoming"
        const val EXTRA_FILE = "file"
        @Suppress("unused") fun intent(ctx: Context) = Intent(ctx, TransferCancelActivity::class.java)
    }
}
