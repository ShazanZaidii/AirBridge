package com.shazan.airbridge.phone

import android.app.Notification
import android.app.Person
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Who is calling, as completely as the phone knows (§13.4): the contact's name, the number's label ("Mobile")
 * and a small round-croppable photo. Contacts are used only when the user allowed them; without, the name and
 * photo the phone app puts in its call notification are used. Unknown numbers are formatted for reading.
 */
object CallerInfo {
    private const val TAG = "AirBridge.Caller"
    private const val PHOTO_PX = 128

    data class Caller(val name: String?, val number: String?, val label: String?, val photoJpgB64: String?)

    /**
     * [title] is the notification's title (a name or a number, bidi marks already removed), [number] a number
     * known from elsewhere (telephony on older Androids).
     */
    fun resolve(ctx: Context, title: String?, number: String?, notification: Notification?): Caller {
        val titleIsNumber = title != null && looksLikeNumber(title)
        val person = callPerson(notification)
        val personTel = person?.uri?.takeIf { it.startsWith("tel:") }?.removePrefix("tel:")?.let { Uri.decode(it) }
        // The dialer's Person often carries the number wrapped in invisible direction marks: clean it first.
        val personName = person?.name?.toString()?.let(::clean)?.takeIf { it.isNotBlank() }
        val num = number?.takeIf { it.isNotBlank() } ?: (if (titleIsNumber) title else null) ?: personTel
            ?: personName?.takeIf { looksLikeNumber(it) }
        val shownName = (if (!titleIsNumber) title?.takeIf { it.isNotBlank() } else null) ?: personName?.takeIf { !looksLikeNumber(it) }

        var name: String? = null
        var label: String? = null
        var photo: Bitmap? = null
        if (PhoneRelaySettings.canReadContacts(ctx)) {
            val hit = num?.let { byNumber(ctx, it) }
                ?: person?.uri?.takeIf { it.startsWith("content:") }?.let { byContactUri(ctx, Uri.parse(it)) }
                ?: shownName?.let { byName(ctx, it) }
            if (hit != null) { name = hit.first; label = hit.second; photo = hit.third }
        }
        if (name == null) name = shownName
        if (photo == null && !PhoneRelaySettings.canReadContacts(ctx)) photo = notificationPhoto(ctx, notification, person)
        val shownNumber = num?.let { format(ctx, it) }
        return Caller(name, shownNumber, label, photo?.let { encode(it) })
    }

    private val NUMBER = Regex("^[+()\\d\\s.\\-]{4,}$")
    private val BIDI = Regex("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]")
    /** Drops the invisible direction marks dialers wrap numbers in. */
    fun clean(s: String): String = s.replace(BIDI, "").trim()
    fun looksLikeNumber(s: String): Boolean = NUMBER.matches(s.trim())

    /**
     * Readable international form of a number with its country code ("+91 98765 43210"), so an unsaved caller
     * is recognisable on the computer. Short codes and anything that isn't a full number stay as the phone gave them.
     */
    private fun format(ctx: Context, number: String): String {
        val iso = try {
            val tm = ctx.getSystemService(TelephonyManager::class.java)
            (tm?.networkCountryIso?.takeIf { it.isNotBlank() } ?: tm?.simCountryIso?.takeIf { it.isNotBlank() } ?: Locale.getDefault().country)
                .uppercase(Locale.ROOT)
        } catch (_: Throwable) { Locale.getDefault().country }
        return try {
            val e164 = PhoneNumberUtils.formatNumberToE164(number, iso)
            if (e164 != null) PhoneNumberUtils.formatNumber(e164, iso) ?: e164
            else PhoneNumberUtils.formatNumber(number, iso) ?: number
        } catch (_: Throwable) { number }
    }

    private fun typeLabel(ctx: Context, type: Int, custom: String?): String? = try {
        ContactsContract.CommonDataKinds.Phone.getTypeLabel(ctx.resources, type, custom)?.toString()?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) { null }

    private fun byNumber(ctx: Context, number: String): Triple<String?, String?, Bitmap?>? = try {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val cols = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.TYPE,
            ContactsContract.PhoneLookup.LABEL, ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI)
        ctx.contentResolver.query(uri, cols, null, null, null)?.use { c ->
            if (!c.moveToFirst()) null
            else Triple(c.getString(0), typeLabel(ctx, c.getInt(1), c.getString(2)), c.getString(3)?.let { loadPhoto(ctx, Uri.parse(it)) })
        }
    } catch (t: Throwable) { Log.w(TAG, "lookup", t); null }

    private fun byContactUri(ctx: Context, contact: Uri): Triple<String?, String?, Bitmap?>? = try {
        val cols = arrayOf(ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts.PHOTO_THUMBNAIL_URI)
        ctx.contentResolver.query(contact, cols, null, null, null)?.use { c ->
            if (!c.moveToFirst()) null else Triple(c.getString(0), null, c.getString(1)?.let { loadPhoto(ctx, Uri.parse(it)) })
        }
    } catch (t: Throwable) { null }

    /** Android 12+ dialers often show only the contact's name: find that contact for its photo (and label if one number). */
    private fun byName(ctx: Context, name: String): Triple<String?, String?, Bitmap?>? = try {
        val cols = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL, ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
        ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?", arrayOf(name), null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val contactIds = HashSet<Long>()
            val labels = HashSet<String?>()
            val shown = c.getString(0)
            val photoUri = c.getString(3)
            do { contactIds += c.getLong(4); labels += typeLabel(ctx, c.getInt(1), c.getString(2)) } while (c.moveToNext())
            if (contactIds.size != 1) return@use null // two contacts with that name: don't guess
            val label = if (labels.size == 1) labels.first() else null
            Triple(shown, label, photoUri?.let { loadPhoto(ctx, Uri.parse(it)) })
        }
    } catch (t: Throwable) { null }

    private fun loadPhoto(ctx: Context, uri: Uri): Bitmap? = try {
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    } catch (_: Throwable) { null }

    @Suppress("DEPRECATION")
    private fun callPerson(n: Notification?): Person? {
        if (n == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) n.extras.getParcelable(EXTRA_CALL_PERSON, Person::class.java)
            else n.extras.getParcelable(EXTRA_CALL_PERSON) as? Person
        } catch (_: Throwable) { null }
    }

    /** The photo the phone app shows in its call notification (the call person's icon, else the large icon). */
    private fun notificationPhoto(ctx: Context, n: Notification?, person: Person?): Bitmap? {
        val icon: Icon? = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) person?.icon else null) ?: n?.getLargeIcon()
        val d = try { icon?.loadDrawable(ctx) } catch (_: Throwable) { null } ?: return null
        return try {
            val w = d.intrinsicWidth.takeIf { it > 0 } ?: PHOTO_PX
            val h = d.intrinsicHeight.takeIf { it > 0 } ?: PHOTO_PX
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, w, h); d.draw(Canvas(b)); b
        } catch (_: Throwable) { null }
    }

    /** Square-cropped, [PHOTO_PX] px, JPEG, base64. The computer crops it round. */
    private fun encode(src: Bitmap): String? = try {
        val side = minOf(src.width, src.height)
        val sq = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(sq, PHOTO_PX, PHOTO_PX, true)
        // JPEG has no alpha: draw on white so transparent corners don't turn black.
        val flat = Bitmap.createBitmap(PHOTO_PX, PHOTO_PX, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(scaled, 0f, 0f, null) }
        val out = ByteArrayOutputStream()
        flat.compress(Bitmap.CompressFormat.JPEG, 82, out)
        java.util.Base64.getEncoder().encodeToString(out.toByteArray())
    } catch (t: Throwable) { Log.w(TAG, "photo", t); null }

    /** `Notification.EXTRA_CALL_PERSON` (API 31; read by name). */
    private const val EXTRA_CALL_PERSON = "android.callPerson"
}
