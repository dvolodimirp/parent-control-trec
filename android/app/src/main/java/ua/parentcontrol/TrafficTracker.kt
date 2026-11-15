package ua.parentcontrol

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.telephony.TelephonyManager
import android.os.Build
import java.util.Calendar

/**
 * Per-app data usage for "today", in bytes.
 *
 * First version of this used TrafficStats.getUidRxBytes()/getUidTxBytes(). That turned
 * out to be the actual bug: those calls are only reliable for the calling app's own UID —
 * for other apps' UIDs they commonly return UNSUPPORTED (-1) on modern Android, which the
 * old code silently skipped, so every app showed zero traffic.
 *
 * NetworkStatsManager.querySummaryForUid() is the correct, documented API for reading
 * another app's usage, and it uses the exact same special permission ("Usage access",
 * PACKAGE_USAGE_STATS) already requested for app-usage minutes in step 1 of the app —
 * so nothing new to grant.
 */
object TrafficTracker {
    fun todayBytes(c: Context, uids: Map<String, Int>): Map<String, Long> {
        if (!UsageHelper.hasAccess(c)) return emptyMap() // same "Usage access" toggle as the minutes tracker
        val nsm = c.getSystemService(NetworkStatsManager::class.java) ?: return emptyMap()
        val now = System.currentTimeMillis()
        val start = startOfDay(now)

        // Mobile data needs a subscriberId on API < 29 (requires READ_PHONE_STATE, which
        // this app does not request) — if unavailable we just skip mobile and still get Wi-Fi.
        val mobileSubscriberId: String? = try {
            if (Build.VERSION.SDK_INT < 29) {
                @Suppress("DEPRECATION", "MissingPermission")
                c.getSystemService(TelephonyManager::class.java)?.subscriberId
            } else null // API 29+: passing null works for the default subscription
        } catch (_: Exception) { null }

        val out = HashMap<String, Long>()
        for ((pkg, uid) in uids) {
            var total = sumForType(nsm, ConnectivityManager.TYPE_WIFI, null, uid, start, now)
            total += sumForType(nsm, ConnectivityManager.TYPE_MOBILE, mobileSubscriberId, uid, start, now)
            out[pkg] = total
        }
        return out
    }

    private fun sumForType(nsm: NetworkStatsManager, networkType: Int, subscriberId: String?, uid: Int, start: Long, end: Long): Long {
        return try {
            val stats = nsm.querySummaryForUid(networkType, subscriberId, start, end, uid)
            var sum = 0L
            val bucket = NetworkStats.Bucket()
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                sum += bucket.rxBytes + bucket.txBytes
            }
            stats.close()
            sum
        } catch (_: Exception) { 0L } // no stats for this type (e.g. no SIM, or never granted) — not fatal
    }

    private fun startOfDay(now: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
