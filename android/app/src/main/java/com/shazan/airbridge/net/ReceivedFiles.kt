package com.shazan.airbridge.net

import android.app.DownloadManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import com.shazan.airbridge.R

/** The files one transfer saved, so the "Received" card and the notification can open them. */
data class ReceivedFiles(val items: List<Item>) {
    data class Item(val uri: Uri, val mime: String)

    /** Opens the file (one) or the AirBridge Transfers folder (several) in whatever app handles it. */
    fun open(ctx: Context) {
        for (i in intents()) {
            try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return }
            catch (_: ActivityNotFoundException) {} catch (_: SecurityException) {}
        }
        Toast.makeText(ctx, ctx.getString(R.string.not_available), Toast.LENGTH_SHORT).show()
    }

    /** For the notification: the same, best option only. */
    fun pendingIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, items.firstOrNull()?.uri?.hashCode() ?: 0, intents().first().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Best first: the file itself, the folder in the Files app, the system Downloads screen. */
    private fun intents(): List<Intent> {
        val out = ArrayList<Intent>()
        if (items.size == 1) {
            out += Intent(Intent.ACTION_VIEW).setDataAndType(items[0].uri, items[0].mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        out += folderIntent()
        out += Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
        return out
    }

    companion object {
        /** Downloads/AirBridge Transfers in the Files app. */
        fun folderIntent(): Intent {
            val doc = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/${Saver.SUBDIR}")
            return Intent(Intent.ACTION_VIEW).setDataAndType(doc, DocumentsContract.Document.MIME_TYPE_DIR)
        }
    }
}
