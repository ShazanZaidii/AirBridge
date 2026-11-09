package com.shazan.airbridge.input

/**
 * Target side of a carried drag (PROTOCOL.md §8.8): which ghost, if any, follows our cursor.
 * Pure state, no Android types, so it can be unit tested. [TargetController] drives it on the main
 * thread and turns the returned [Effect]s into overlay changes.
 */
class DragCarryTarget {

    /** What the controller is carrying. Display only: the files themselves arrive as a §6 transfer. */
    data class Info(
        val id: Long,
        val from: String,
        val names: List<String>,
        val kinds: List<String>,
        val count: Int,
        val totalBytes: Long,
    ) {
        /** Kind of the first item, used for the ghost icon. */
        val kind: String get() = kinds.firstOrNull() ?: "file"
        val firstName: String get() = names.firstOrNull().orEmpty()
    }

    sealed interface Effect {
        object None : Effect
        data class Show(val info: Info) : Effect
        object Hide : Effect
        /** Dropped on us: settle the ghost (if still shown) and say what is coming. */
        data class Dropped(val info: Info, val ghostWasShown: Boolean) : Effect
    }

    /** The ghost on screen right now. */
    var shown: Info? = null
        private set

    /**
     * The last carry that was hidden by a leave/lease change rather than a cancel. A `drag_drop` can race
     * with our own `ctl_leave` (the user lets go just as the cursor is pushed back): the files still come,
     * so the confirmation should still show.
     */
    private var recent: Info? = null

    /** `drag_carry` from [from]; [holder] is the peer holding our explicit (ctl_enter) lease, or null. */
    fun onCarry(from: String, holder: String?, info: Info): Effect {
        if (holder == null || from != holder || info.from != from) return Effect.None
        shown = info
        recent = null
        return Effect.Show(info)
    }

    fun onDrop(from: String, id: Long): Effect {
        val s = shown
        if (s != null && s.from == from && s.id == id) {
            shown = null
            recent = null
            return Effect.Dropped(s, ghostWasShown = true)
        }
        val r = recent
        if (r != null && r.from == from && r.id == id) {
            recent = null
            return Effect.Dropped(r, ghostWasShown = false)
        }
        return Effect.None
    }

    fun onCancel(from: String, id: Long): Effect {
        val r = recent
        if (r != null && r.from == from && r.id == id) recent = null
        val s = shown ?: return Effect.None
        if (s.from != from || s.id != id) return Effect.None
        shown = null
        return Effect.Hide
    }

    /** `ctl_leave` either way, pre-emption, lease expiry, "stop being controlled": the ghost goes. */
    fun onLeave(peer: String?): Effect {
        val s = shown ?: return Effect.None
        if (peer != null && s.from != peer) return Effect.None
        shown = null
        recent = s
        return Effect.Hide
    }

    /** The holder's session ended: nothing more will come from it. */
    fun onPeerGone(peer: String): Effect {
        if (recent?.from == peer) recent = null
        val s = shown ?: return Effect.None
        if (s.from != peer) return Effect.None
        shown = null
        return Effect.Hide
    }

    companion object {
        /** At most this many names are shown (the protocol sends ≤ 3). */
        const val MAX_NAMES = 3
        private val KINDS = setOf("image", "video", "audio", "folder", "file")

        /** Builds [Info] from raw message fields, tolerating missing or odd values. Null if [id] is missing. */
        fun info(from: String, id: Long?, names: List<String>, kinds: List<String>, count: Int?, totalBytes: Long?): Info? {
            if (id == null || id < 0) return null
            val n = names.map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_NAMES).map { it.take(200) }
            val k = n.indices.map { i -> kinds.getOrNull(i)?.takeIf { it in KINDS } ?: "file" }
            val c = (count ?: n.size).coerceAtLeast(n.size).coerceAtLeast(1)
            return Info(id, from, n, k, c, (totalBytes ?: 0L).coerceAtLeast(0L))
        }
    }
}
