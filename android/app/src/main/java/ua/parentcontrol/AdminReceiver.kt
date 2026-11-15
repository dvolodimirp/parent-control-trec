package ua.parentcontrol

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class AdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(c: Context, i: Intent): CharSequence =
        "Це вимкне батьківський контроль на пристрої."

    override fun onDisabled(c: Context, i: Intent) {
        // Повідомляємо сервер, що захист знято (best-effort, без затримки UI).
        Thread {
            try {
                val token = sp(c).getString("token", null) ?: return@Thread
                Api.call(c, "POST", "/api/device/sync",
                    org.json.JSONObject().put("day", UsageHelper.day())
                        .put("usage", org.json.JSONArray()).put("apps", org.json.JSONArray())
                        .put("admin_active", false), token)
            } catch (_: Exception) {}
        }.start()
    }
}
