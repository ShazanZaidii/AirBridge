package com.shazan.airbridge.phone

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * "AirBridge Notifications": the system binds it once the user allows notification access. It only hands
 * events to [PhoneRelay], which does nothing unless "Show phone notifications on your computers" (or calls)
 * is on in AirBridge's Settings.
 */
class PhoneNotifListener : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
        Log.i(TAG, "listener connected")
        PhoneRelay.onListenerConnected()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        Log.i(TAG, "listener disconnected")
        PhoneRelay.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        sbn ?: return
        PhoneRelay.onPosted(sbn, importanceOf(sbn, rankingMap))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        sbn ?: return
        PhoneRelay.onRemoved(sbn)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** All notifications on the phone now (empty if not connected). */
    fun snapshot(): List<Pair<StatusBarNotification, Int>> = try {
        val ranking = currentRanking
        (activeNotifications ?: emptyArray()).map { it to importanceOf(it, ranking) }
    } catch (t: Throwable) {
        Log.w(TAG, "activeNotifications", t); emptyList()
    }

    fun dismiss(sbnKey: String): Boolean = try { cancelNotification(sbnKey); true } catch (t: Throwable) { Log.w(TAG, "cancel", t); false }

    companion object {
        private const val TAG = "AirBridge.NotifL"

        @Volatile var instance: PhoneNotifListener? = null
            private set

        private fun importanceOf(sbn: StatusBarNotification, map: RankingMap?): Int {
            if (map == null || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return NotifFilter.IMPORTANCE_DEFAULT
            val r = Ranking()
            return if (map.getRanking(sbn.key, r)) r.importance else NotifFilter.IMPORTANCE_DEFAULT
        }
    }
}
