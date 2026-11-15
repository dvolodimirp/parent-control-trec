package ua.parentcontrol

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * Takes over the screen the instant a blocked app/limit is hit, then returns home.
 * Replaces the old performGlobalAction(HOME) approach, whose result depended on
 * whatever the device's launcher does with HOME (some launchers opened the app
 * drawer instead of the home screen). This activity is always ours, so the
 * behaviour is the same on every device.
 */
class BlockedActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        val tv = TextView(this).apply {
            text = intent.getStringExtra("reason") ?: "Додаток заблоковано"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(60, 60, 60, 60)
        }
        setContentView(tv)
        window.decorView.setBackgroundColor(Color.parseColor("#1C2630"))
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }, 1100)
    }

    @Suppress("MissingSuperCall")
    override fun onBackPressed() { /* swallow back while this is shown */ }
}
