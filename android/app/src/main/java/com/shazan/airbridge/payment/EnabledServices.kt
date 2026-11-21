package com.shazan.airbridge.payment

/** Edits of the `enabled_accessibility_services` string ("a/b:c/.d"). Pure, unit-tested. */
object EnabledServices {
    fun split(s: String?): List<String> = s?.split(':')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    /** "pkg/.Cls" and "pkg/pkg.Cls" name the same service. */
    fun normal(entry: String): String {
        val pkg = entry.substringBefore('/')
        val cls = entry.substringAfter('/', "")
        return if (cls.startsWith(".")) "$pkg/$pkg$cls" else entry
    }

    fun ours(enabled: String?, pkg: String): List<String> = split(enabled).filter { it.substringBefore('/') == pkg }

    /** [enabled] without the services of [pkg], and the entries that were taken out. */
    fun remove(enabled: String?, pkg: String): Pair<String, List<String>> {
        val all = split(enabled)
        val (gone, kept) = all.partition { it.substringBefore('/') == pkg }
        return kept.joinToString(":") to gone
    }

    /** [enabled] with [removed] added again where they are missing; the rest keeps its order. */
    fun restore(enabled: String?, removed: List<String>): String {
        val cur = split(enabled)
        val have = cur.map(::normal).toSet()
        return (cur + removed.filter { normal(it) !in have }).joinToString(":")
    }
}
