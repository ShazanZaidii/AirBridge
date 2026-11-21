package com.shazan.airbridge.clipboard

/**
 * Which clipboard images and files are worth sending without being asked (docs/protocol.md 5.1). Pure, so it can be
 * unit-tested. Bigger things are never sent automatically.
 */
object ClipMedia {
    const val IMAGE_MAX_BYTES = 25L * 1024 * 1024
    const val FILES_MAX_BYTES = 100L * 1024 * 1024
    const val FILES_MAX_COUNT = 20

    /** One item whose type is an image type (it starts with "image/"). */
    fun isImage(mimes: List<String?>): Boolean = mimes.size == 1 && mimes[0]?.startsWith("image/") == true

    /** Whether items of these [sizes] may go over the clipboard. An image has a lower limit than files. */
    fun allowed(sizes: List<Long>, image: Boolean): Boolean {
        if (sizes.isEmpty() || sizes.any { it < 0 }) return false
        val total = sizes.sum()
        return if (image) total <= IMAGE_MAX_BYTES else sizes.size <= FILES_MAX_COUNT && total <= FILES_MAX_BYTES
    }
}
