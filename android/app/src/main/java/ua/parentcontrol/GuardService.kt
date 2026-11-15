package ua.parentcontrol

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.accessibility.AccessibilityEvent
import org.json.JSONArray
import org.json.JSONObject

/** Enforces the policy (blocking) and syncs with the server every 20s. */
class GuardService : AccessibilityService() {
    private var thread: HandlerThread? = null
    private var h: Handler? = null
    @Volatile private var policy: Policy? = null
    @Volatile private var usage: Map<String, Int> = emptyMap()
    private var usageAt = 0L
    private var blockedAt = 0L
    private var ignored: Set<String> = emptySet()
    @Volatile private var lastCmdResult: String? = null

    /** Backup for the receiver below: on some OEMs ACTION_USER_PRESENT can be delayed
        or suppressed by battery optimisation. This loop guarantees a relock within at
        most ~2s of any unlock, regardless of whether the broadcast arrives at all. */
    private val lockEnforcer = object : Runnable {
        override fun run() { if (shouldStayLocked()) lockScreen(); h?.postDelayed(this, 2_000) }
    }

    /** Fires the instant the user unlocks the keyguard, for an immediate relock on top
        of the 2s backbone loop above. */
    private val userPresentReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // small delay: calling lockNow() while the unlock animation is still running
            // can silently no-op on some Android builds.
            h?.postDelayed({ if (shouldStayLocked()) lockScreen() }, 300)
        }
    }

    private val loop = object : Runnable {
        override fun run() { sync(); h?.postDelayed(this, 20_000) }
    }

    override fun onServiceConnected() {
        ignored = UsageHelper.ignored(this)
        sp(this).getString("policy", null)?.let { policy = Policy(JSONObject(it)) }
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(userPresentReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(userPresentReceiver, filter)
        thread = HandlerThread("sync").also { it.start() }
        h = Handler(thread!!.looper).also { it.post(loop); it.post(lockEnforcer) }
    }

    override fun onDestroy() {
        h?.removeCallbacksAndMessages(null); thread?.quit()
        try { unregisterReceiver(userPresentReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
    override fun onInterrupt() {}

    private fun sync() {
        val s = sp(this); val token = s.getString("token", null) ?: return
        try {
            val labels = UsageHelper.launchable(this)
            val u = if (UsageHelper.hasAccess(this)) UsageHelper.today(this) else emptyMap()
            usage = u; usageAt = System.currentTimeMillis()
            val day = UsageHelper.day()
            val uids = UsageHelper.uids(this, labels.keys)
            val traffic = TrafficTracker.todayBytes(this, uids)
            val dpm = getSystemService(DevicePolicyManager::class.java)
            val adminActive = dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java))
            val versionCode = try {
                val pi = packageManager.getPackageInfo(packageName, 0)
                if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
            } catch (_: Exception) { 0 }
            val vpnConsent = VpnService.prepare(this) == null
            val body = JSONObject()
                .put("day", day)
                .put("admin_active", adminActive)
                .put("app_version_code", versionCode)
                .put("last_cmd_result", lastCmdResult ?: JSONObject.NULL)
                .put("vpn_consent", vpnConsent)
                .put("net_filter_running", NetGuardService.isRunning())
                .put("net_filter_error", NetGuardService.lastError ?: JSONObject.NULL)
                .put("location", lastKnownLocation() ?: JSONObject.NULL)
                .put("update_status", UpdateStatus.last ?: JSONObject.NULL)
                .put("usage", JSONArray().also { a -> u.filter { it.key in labels }.forEach { (p, m) ->
                    a.put(JSONObject().put("pkg", p).put("label", labels[p]).put("minutes", m)) } })
                .put("traffic", JSONArray().also { a -> traffic.forEach { (p, bytes) ->
                    a.put(JSONObject().put("pkg", p).put("label", labels[p] ?: p).put("bytes", bytes)) } })
                .put("apps", JSONArray().also { a -> labels.forEach { (p, l) -> a.put(JSONObject().put("pkg", p).put("label", l)) } })
            val r = Api.call(this, "POST", "/api/device/sync", body, token)
            val p = r.getJSONObject("policy")
            s.edit().putString("policy", p.toString()).apply()
            policy = Policy(p)
            applyNetFilter(policy!!)
            val cmds = r.getJSONArray("commands")
            for (i in 0 until cmds.length()) {
                lastCmdResult = when (cmds.getString(i)) {
                    "flashlight_on" -> Flashlight.set(this, true)
                    "flashlight_off" -> Flashlight.set(this, false)
                    "alarm" -> Alarm.sound(this)
                    else -> lastCmdResult
                }
            }
            if (!r.isNull("update")) {
                val upd = r.getJSONObject("update")
                Updater.maybeInstall(this, upd.getInt("version_code"), upd.getString("url"))
            }
        } catch (e: Exception) {
            if (e.message?.startsWith("HTTP 401") == true) s.edit().remove("token").apply()
        }
    }

    /** Reads the last known position from whichever providers the OS already has a fix for
        (no active GPS request — cheaper on battery, but can be stale if nothing else on the
        device has asked for location recently). Needs the permission granted in step 5
        of the app; returns null silently if not granted or nothing is known yet. */
    private fun lastKnownLocation(): JSONObject? {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = getSystemService(LocationManager::class.java) ?: return null
        var best: android.location.Location? = null
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused")) {
            try {
                val loc = lm.getLastKnownLocation(provider) ?: continue
                if (best == null || loc.time > best!!.time) best = loc
            } catch (_: Exception) {}
        }
        val b = best ?: return null
        return JSONObject().put("lat", b.latitude).put("lon", b.longitude)
            .put("accuracy", b.accuracy).put("ts", b.time)
    }

    private fun applyNetFilter(p: Policy) {
        NetGuardService.blockedDomains = p.blockedDomains
        val running = NetGuardService.isRunning()
        if (p.netFilterEnabled && !running) {
            // Requires the one-time VPN consent granted earlier in MainActivity ("4. Мережевий фільтр").
            if (VpnService.prepare(this) == null) {
                startService(Intent(this, NetGuardService::class.java))
            } else {
                NetGuardService.lastError = "немає дозволу на VPN (не виконано пункт 4 у додатку)"
            }
        } else if (!p.netFilterEnabled && running) {
            stopService(Intent(this, NetGuardService::class.java))
        }
    }

    private fun refreshUsageIfStale() {
        if (System.currentTimeMillis() - usageAt > 10_000 && UsageHelper.hasAccess(this)) {
            usage = UsageHelper.today(this); usageAt = System.currentTimeMillis()
        }
    }

    /** True while the whole device should stay locked: either the parent locked it manually,
        or today's overall time limit has been used up (resets itself at midnight, since
        `usage` and the daily limit are both scoped to "today"). */
    private fun shouldStayLocked(): Boolean {
        val p = policy ?: return false
        if (p.locked) return true
        if (p.daily <= 0) return false
        refreshUsageIfStale()
        val total = usage.filter { it.key !in ignored && it.key !in p.quietAllowed }.values.sum()
        return total >= p.daily
    }

    private fun lockScreen() {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        if (dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java))) dpm.lockNow()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg in ignored || pkg.contains("incallui") || pkg.contains("emergency")) return
        val p = policy ?: return
        if (shouldStayLocked()) { lockScreen(); return }
        val why = violation(p, pkg) ?: return
        val now = System.currentTimeMillis()
        if (now - blockedAt > 2000) {
            blockedAt = now
            startActivity(Intent(this, BlockedActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("reason", why))
        }
    }

    /** Violations that only close the one app, never the whole device — per-app block,
        per-app time limit, and apps outside the quiet-hours allow-list. The overall daily
        limit is handled separately in shouldStayLocked() as a full device lock instead. */
    private fun violation(p: Policy, pkg: String): String? {
        if (pkg in p.blocked) return "Додаток заблоковано батьками"
        if (p.isQuiet() && pkg !in p.quietAllowed) return "Зараз тихий час"
        refreshUsageIfStale()
        p.limits[pkg]?.let { if ((usage[pkg] ?: 0) >= it) return "Ліміт часу для додатку вичерпано" }
        return null
    }
}
