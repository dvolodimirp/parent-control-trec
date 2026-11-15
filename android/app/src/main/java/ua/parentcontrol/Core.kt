package ua.parentcontrol

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

fun sp(c: Context) = c.getSharedPreferences("pc", Context.MODE_PRIVATE)

private fun JSONArray?.strings(): Set<String> =
    if (this == null) emptySet() else (0 until length()).map { getString(it) }.toSet()
private fun hm(s: String): Int = if (s.length == 5) s.substring(0, 2).toInt() * 60 + s.substring(3).toInt() else -1

class Policy(j: JSONObject) {
    val locked = j.optBoolean("locked")
    val daily = j.optInt("daily_limit")
    val limits: Map<String, Int> = j.optJSONObject("app_limits")?.let { o -> o.keys().asSequence().associateWith { o.getInt(it) } } ?: emptyMap()
    val blocked = j.optJSONArray("blocked").strings()
    val quietAllowed = j.optJSONArray("quiet_allowed").strings()
    val blockedDomains = j.optJSONArray("blocked_domains").strings()
    val netFilterEnabled = j.optBoolean("net_filter_enabled")
    private val qs = hm(j.optString("quiet_start"))
    private val qe = hm(j.optString("quiet_end"))

    fun isQuiet(): Boolean {
        if (qs < 0 || qe < 0 || qs == qe) return false
        val c = Calendar.getInstance(); val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return if (qs < qe) now in qs until qe else now >= qs || now < qe
    }
}

object Api {
    fun call(c: Context, method: String, path: String, body: JSONObject?, token: String?): JSONObject {
        val base = sp(c).getString("server", "")!!.trimEnd('/')
        val h = URL(base + path).openConnection() as HttpURLConnection
        h.requestMethod = method; h.connectTimeout = 10000; h.readTimeout = 10000
        token?.let { h.setRequestProperty("X-Device-Token", it) }
        if (body != null) {
            h.doOutput = true; h.setRequestProperty("Content-Type", "application/json")
            h.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val code = h.responseCode
        val text = (if (code < 400) h.inputStream else h.errorStream).bufferedReader().readText()
        if (code >= 400) throw IOException("HTTP $code: $text")
        return JSONObject(text)
    }

    /** Opens a GET connection for binary download (used for APK updates). Caller must read/close the stream. */
    fun openStream(c: Context, path: String, token: String): HttpURLConnection {
        val base = sp(c).getString("server", "")!!.trimEnd('/')
        val h = URL(base + path).openConnection() as HttpURLConnection
        h.requestMethod = "GET"; h.connectTimeout = 15000; h.readTimeout = 30000
        h.setRequestProperty("X-Device-Token", token)
        if (h.responseCode != 200) throw IOException("HTTP ${h.responseCode}")
        return h
    }
}

object UsageHelper {
    fun day(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun hasAccess(c: Context): Boolean {
        val a = c.getSystemService(AppOpsManager::class.java)
        return a.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), c.packageName) == AppOpsManager.MODE_ALLOWED
    }

    /** pkg -> label for all launchable apps (except this app and launchers) */
    fun launchable(c: Context): Map<String, String> {
        val pm = c.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val skip = ignored(c)
        return pm.queryIntentActivities(i, 0).map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first !in skip }.toMap()
    }

    fun ignored(c: Context): Set<String> {
        val s = mutableSetOf(c.packageName, "com.android.systemui")
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        c.packageManager.queryIntentActivities(home, 0).forEach { s += it.activityInfo.packageName }
        return s
    }

    /** pkg -> uid, needed to read TrafficStats per app */
    fun uids(c: Context, pkgs: Collection<String>): Map<String, Int> {
        val pm = c.packageManager
        val out = HashMap<String, Int>()
        for (p in pkgs) {
            try { out[p] = pm.getApplicationInfo(p, 0).uid } catch (_: Exception) {}
        }
        return out
    }

    /** minutes used today per package */
    fun today(c: Context): Map<String, Int> {
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val usm = c.getSystemService(android.app.usage.UsageStatsManager::class.java)
        return usm.queryAndAggregateUsageStats(cal.timeInMillis, System.currentTimeMillis())
            .mapValues { (it.value.totalTimeInForeground / 60000).toInt() }
    }
}
