package ua.parentcontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * Receives the result of a PackageInstaller session started by Updater.
 *
 * Audit fix: the old version only reacted to STATUS_PENDING_USER_ACTION and silently
 * dropped every other outcome (STATUS_SUCCESS, STATUS_FAILURE and its sub-codes like a
 * signature mismatch or corrupted APK) — so a failed install looked identical to nothing
 * happening at all. Every branch now records a human-readable outcome via UpdateStatus,
 * which GuardService forwards to the server on the next sync.
 *
 * One real, unresolved risk worth stating plainly: starting the confirmation Activity
 * below happens from a BroadcastReceiver with no foreground Activity of our own behind
 * it. Android 10+ restricts background activity starts, and depending on the exact OS
 * version/OEM this *may* silently fail to show anything even though PackageInstaller
 * itself did everything right on our end. If updates keep reporting "очікую
 * підтвердження" without ever resolving, that background-start restriction — not this
 * code — is the most likely explanation; Device Owner mode sidesteps it entirely, since
 * Device Owner installs never need a confirmation screen in the first place.
 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm == null) {
                    UpdateStatus.last = "система попросила підтвердження, але не надала намір для нього"
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    c.startActivity(confirm)
                    UpdateStatus.last = "показано запит підтвердження встановлення на пристрої"
                } catch (ex: Exception) {
                    UpdateStatus.last = "не вдалось показати запит підтвердження: ${ex.message ?: ex.javaClass.simpleName}"
                }
            }
            PackageInstaller.STATUS_SUCCESS ->
                UpdateStatus.last = "оновлення встановлено успішно"
            else ->
                UpdateStatus.last = "встановлення НЕ вдалось (код $status): ${message ?: "без деталей від системи"}"
        }
    }
}
