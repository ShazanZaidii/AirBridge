package com.shazan.airbridge.phone

import org.json.JSONArray
import org.json.JSONObject

/** Plain maps (as built by [NotifWire]) ↔ org.json. */
object JsonMaps {
    fun toJson(m: Map<String, Any?>): JSONObject {
        val o = JSONObject()
        for ((k, v) in m) if (v != null) o.put(k, wrap(v))
        return o
    }

    private fun wrap(v: Any): Any = when (v) {
        is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            toJson(v as Map<String, Any?>)
        }
        is List<*> -> JSONArray().also { a -> v.forEach { if (it != null) a.put(wrap(it)) } }
        else -> v
    }

    /** Top-level fields only (nested objects stay as they are); enough for the sink → source commands. */
    fun fromJson(o: JSONObject): Map<String, Any?> {
        val out = HashMap<String, Any?>()
        for (k in o.keys()) {
            val v = o.opt(k)
            out[k] = if (v == JSONObject.NULL) null else v
        }
        return out
    }
}
