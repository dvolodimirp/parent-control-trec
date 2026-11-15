package ua.parentcontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Заглушка: Accessibility-служба Android вмикає сама після перезавантаження,
    якщо користувач уже дав дозвіл — додаткового коду не потрібно. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {}
}
