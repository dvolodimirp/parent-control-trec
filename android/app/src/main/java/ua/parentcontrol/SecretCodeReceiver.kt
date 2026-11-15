package ua.parentcontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Дозволяє батькам знову відкрити приховане меню: набрати *#*#7273#*#* в Телефоні. */
class SecretCodeReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
