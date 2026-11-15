package ua.parentcontrol

import android.content.Context
import android.content.pm.PackageInstaller
import android.app.PendingIntent
import android.content.Intent
import android.os.Build

/**
 * Downloads and installs an update pushed from the parent server.
 * If this device was made a Device Owner (see README), the install is fully silent.
 * Otherwise Android still shows its normal one-tap "Install" confirmation —
 * there is no public API to skip that step for a regular (non-owner) app.
 *
 * Audit fix: the old version marked a versionCode as "tried" the moment session.commit()
 * returned without throwing, and never touched it again. commit() succeeding only means
 * the request was queued — it says nothing about whether the install actually completed.
 * If the confirmation UI silently failed to show (a real risk: InstallReceiver is a
 * BroadcastReceiver with no foreground Activity of our own behind it, and Android 10+
 * restricts background activity starts) or the install later failed for any other reason,
 * the old code would never try that version again. This now retries periodically instead
 * of giving up forever after one attempt, and records what actually happened via
 * UpdateStatus so a stuck update is visible in the parent panel instead of silent.
 */
object Updater {
    private const val RETRY_INTERVAL_MS = 30 * 60 * 1000L // retry this version every 30 min until it actually installs

    fun maybeInstall(c: Context, versionCode: Int, path: String) {
        val p = sp(c)
        val triedVersion = p.getInt("update_tried_version", 0)
        val triedAt = p.getLong("update_tried_at", 0)
        val now = System.currentTimeMillis()
        if (triedVersion == versionCode && now - triedAt < RETRY_INTERVAL_MS) return
        val token = p.getString("token", null) ?: return
        p.edit().putInt("update_tried_version", versionCode).putLong("update_tried_at", now).apply()
        UpdateStatus.last = "завантаження оновлення…"
        try {
            val conn = Api.openStream(c, path, token)
            val installer = c.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)
            session.openWrite("update", 0, conn.contentLengthLong).use { out ->
                conn.inputStream.use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(
                c, sessionId, Intent(c, InstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or flags)
            session.commit(pending.intentSender)
            session.close()
            UpdateStatus.last = "запит на встановлення надіслано, очікую підтвердження на пристрої"
        } catch (ex: Exception) {
            UpdateStatus.last = "помилка завантаження/встановлення: ${ex.message ?: ex.javaClass.simpleName}"
        }
    }
}
