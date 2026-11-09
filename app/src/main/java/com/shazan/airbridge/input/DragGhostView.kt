package com.shazan.airbridge.input

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.ViewOutlineProvider
import kotlin.math.max
import kotlin.math.min

/**
 * The card that follows the virtual cursor while a controller carries files over (PROTOCOL.md §8.8):
 * a small graphite card with a kind icon, the name (or "3 items") and the size, tilted a little, with a
 * soft shadow. A second card peeks out behind it when several items are carried. Drawn by hand so it
 * needs no layout inflation inside the accessibility overlay.
 */
class DragGhostView(ctx: Context) : View(ctx) {

    private val d = ctx.resources.displayMetrics.density
    private val cardW = 196 * d
    private val cardH = 56 * d
    private val pad = 10 * d
    private val radius = 14 * d
    /** Room around the card for the back card, the badge and the tilt. */
    private val margin = 12 * d

    private val cardFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F22A2C30") }
    private val backFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E03A3D42") }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f * d; color = Color.parseColor("#33FFFFFF")
    }
    private val tile = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3D4046") }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.6f * d; color = Color.parseColor("#E6E8EB")
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val glyphFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E6E8EB") }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 14 * d; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#A3A7AD"); textSize = 12 * d }
    private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val badgeText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1F2023"); textSize = 11 * d; textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private var title = ""
    private var subtitle = ""
    private var kind = "file"
    private var count = 1
    private val rect = RectF()
    private val inner = RectF()
    private val path = Path()

    /** Card size including the margin; the overlay sizes the view with these. */
    val fullW: Int get() = (cardW + 2 * margin).toInt()
    val fullH: Int get() = (cardH + 2 * margin).toInt()

    /** Offset of the card's top-left corner from the view's top-left (where the cursor tip should sit near). */
    val cardInset: Float get() = margin

    init {
        rotation = -4f
        elevation = 16f // below the cursor dot (20f), so the dot stays on top
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(margin.toInt(), margin.toInt(), (margin + cardW).toInt(), (margin + cardH).toInt(), radius)
                outline.alpha = 0.5f
            }
        }
        pivotX = margin
        pivotY = margin
    }

    fun bind(title: String, subtitle: String, kind: String, count: Int) {
        this.title = title
        this.subtitle = subtitle
        this.kind = kind
        this.count = max(1, count)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val l = margin
        val t = margin
        if (count > 1) {
            canvas.save()
            canvas.rotate(5f, l + cardW / 2, t + cardH / 2)
            rect.set(l + 4 * d, t + 3 * d, l + cardW + 4 * d, t + cardH + 3 * d)
            canvas.drawRoundRect(rect, radius, radius, backFill)
            canvas.restore()
        }
        rect.set(l, t, l + cardW, t + cardH)
        canvas.drawRoundRect(rect, radius, radius, cardFill)
        canvas.drawRoundRect(rect, radius, radius, stroke)

        val ts = cardH - 2 * pad
        rect.set(l + pad, t + pad, l + pad + ts, t + pad + ts)
        canvas.drawRoundRect(rect, 9 * d, 9 * d, tile)
        drawGlyph(canvas, rect)

        val textL = l + pad + ts + 10 * d
        val textW = l + cardW - pad - textL
        val tt = TextUtils.ellipsize(title, titlePaint, textW, TextUtils.TruncateAt.MIDDLE).toString()
        if (subtitle.isEmpty()) {
            canvas.drawText(tt, textL, t + cardH / 2 - (titlePaint.ascent() + titlePaint.descent()) / 2, titlePaint)
        } else {
            canvas.drawText(tt, textL, t + cardH / 2 - 3 * d, titlePaint)
            val st = TextUtils.ellipsize(subtitle, subPaint, textW, TextUtils.TruncateAt.END).toString()
            canvas.drawText(st, textL, t + cardH / 2 + 13 * d, subPaint)
        }

        if (count > 1) {
            val label = if (count > 99) "99+" else count.toString()
            val r = max(10 * d, badgeText.measureText(label) / 2 + 6 * d)
            val cx = l + cardW - 2 * d
            val cy = t + 2 * d
            rect.set(cx - r, cy - 10 * d, cx + r, cy + 10 * d)
            canvas.drawRoundRect(rect, 10 * d, 10 * d, badgeFill)
            canvas.drawText(label, cx, cy - (badgeText.ascent() + badgeText.descent()) / 2, badgeText)
        }
    }

    /** Simple line glyphs per kind; quiet on purpose. */
    private fun drawGlyph(c: Canvas, r: RectF) {
        val s = min(r.width(), r.height())
        val cx = r.centerX()
        val cy = r.centerY()
        val u = s / 24f
        path.reset()
        when (kind) {
            "folder" -> {
                path.moveTo(cx - 8 * u, cy - 5 * u)
                path.lineTo(cx - 3 * u, cy - 5 * u)
                path.lineTo(cx - 1 * u, cy - 3 * u)
                path.lineTo(cx + 8 * u, cy - 3 * u)
                path.lineTo(cx + 8 * u, cy + 6 * u)
                path.lineTo(cx - 8 * u, cy + 6 * u)
                path.close()
                c.drawPath(path, glyph)
            }
            "image" -> {
                inner.set(cx - 8 * u, cy - 6 * u, cx + 8 * u, cy + 6 * u)
                c.drawRoundRect(inner, 2 * u, 2 * u, glyph)
                path.moveTo(cx - 7 * u, cy + 5 * u)
                path.lineTo(cx - 2 * u, cy - 1 * u)
                path.lineTo(cx + 2 * u, cy + 3 * u)
                path.lineTo(cx + 4 * u, cy + 1 * u)
                path.lineTo(cx + 7 * u, cy + 5 * u)
                c.drawPath(path, glyph)
                c.drawCircle(cx + 3.5f * u, cy - 2.5f * u, 1.4f * u, glyphFill)
            }
            "video" -> {
                inner.set(cx - 8 * u, cy - 6 * u, cx + 8 * u, cy + 6 * u)
                c.drawRoundRect(inner, 2 * u, 2 * u, glyph)
                path.moveTo(cx - 2 * u, cy - 3 * u)
                path.lineTo(cx + 3.5f * u, cy)
                path.lineTo(cx - 2 * u, cy + 3 * u)
                path.close()
                c.drawPath(path, glyphFill)
            }
            "audio" -> {
                c.drawLine(cx + 3 * u, cy - 7 * u, cx + 3 * u, cy + 4 * u, glyph)
                c.drawLine(cx + 3 * u, cy - 7 * u, cx + 7 * u, cy - 5 * u, glyph)
                c.drawCircle(cx, cy + 4 * u, 3 * u, glyph)
            }
            else -> {
                path.moveTo(cx - 6 * u, cy - 8 * u)
                path.lineTo(cx + 2 * u, cy - 8 * u)
                path.lineTo(cx + 6 * u, cy - 4 * u)
                path.lineTo(cx + 6 * u, cy + 8 * u)
                path.lineTo(cx - 6 * u, cy + 8 * u)
                path.close()
                c.drawPath(path, glyph)
                path.reset()
                path.moveTo(cx + 2 * u, cy - 8 * u)
                path.lineTo(cx + 2 * u, cy - 4 * u)
                path.lineTo(cx + 6 * u, cy - 4 * u)
                c.drawPath(path, glyph)
            }
        }
    }
}
