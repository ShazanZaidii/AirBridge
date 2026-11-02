package com.shazan.airbridge

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.shazan.airbridge.net.PairedPeer
import com.shazan.airbridge.ui.theme.Palette

/**
 * Direct Share targets, one long-lived dynamic shortcut per paired peer, matched to the
 * `<share-target>` in res/xml/shortcuts.xml by [CATEGORY].
 */
object DirectShare {
    const val CATEGORY = "com.shazan.airbridge.category.SHARE_TARGET"
    private const val PREFIX = "peer_"

    fun shortcutId(peerId: String) = PREFIX + peerId
    fun peerIdFromShortcut(id: String?): String? = id?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)

    @DrawableRes
    fun platformIcon(plat: String): Int = when (plat) {
        "macos" -> R.drawable.ic_mac
        "windows", "linux" -> R.drawable.ic_windows
        else -> R.drawable.ic_phone
    }

    fun publish(ctx: Context, peers: List<PairedPeer>) {
        try {
            val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(ctx).coerceIn(1, 4)
            val wanted = peers.take(max)
            val ids = wanted.map { shortcutId(it.id) }.toSet()
            val existing = ShortcutManagerCompat.getShortcuts(
                ctx, ShortcutManagerCompat.FLAG_MATCH_DYNAMIC or ShortcutManagerCompat.FLAG_MATCH_CACHED,
            ).map { it.id }
            val stale = existing.filter { it !in ids }
            if (stale.isNotEmpty()) {
                ShortcutManagerCompat.removeLongLivedShortcuts(ctx, stale)
                ShortcutManagerCompat.removeDynamicShortcuts(ctx, stale)
            }
            wanted.forEachIndexed { rank, p -> ShortcutManagerCompat.pushDynamicShortcut(ctx, build(ctx, p, rank)) }
        } catch (e: Exception) {
            Log.w("AirBridge", "Direct Share shortcuts update failed", e)
        }
    }

    private fun build(ctx: Context, p: PairedPeer, rank: Int): ShortcutInfoCompat {
        val icon = IconCompat.createWithBitmap(circleIcon(ctx, platformIcon(p.plat)))
        val person = Person.Builder().setName(p.name).setKey(p.id).setIcon(icon).build()
        return ShortcutInfoCompat.Builder(ctx, shortcutId(p.id))
            .setShortLabel(p.name.take(24).ifBlank { "Device" })
            .setLongLabel(p.name.ifBlank { "Device" })
            .setIcon(icon)
            // Launcher use of the shortcut just opens the app; sharing goes to ShareActivity via the category.
            .setIntent(Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_VIEW))
            .setLongLived(true)
            .setPerson(person)
            .setCategories(setOf(CATEGORY))
            .setRank(rank)
            .build()
    }

    private fun circleIcon(ctx: Context, @DrawableRes res: Int): Bitmap {
        val size = 192
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.argb(Palette.SURFACE_HIGH) })
        ContextCompat.getDrawable(ctx, res)?.let {
            val pad = size / 4
            it.setBounds(pad, pad, size - pad, size - pad)
            it.draw(canvas)
        }
        return bmp
    }
}
