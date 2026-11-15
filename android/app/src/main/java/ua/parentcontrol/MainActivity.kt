package ua.parentcontrol

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.*
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var status: TextView
    private val VPN_REQUEST = 7001
    private val LOC_REQUEST = 7002
    private val LOC_BG_REQUEST = 7003

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = sp(this)
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40) }
        fun edit(hint: String, v: String) = EditText(this).apply { this.hint = hint; setText(v); setSingleLine() }.also { l.addView(it) }
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }.also { l.addView(it) }
        fun note(t: String) = TextView(this).apply { text = t; setPadding(0, 8, 0, 16) }.also { l.addView(it) }
        val server = edit("URL сервера (http://IP:8000)", p.getString("server", "") ?: "")
        val code = edit("Код підключення (з панелі батьків)", "")
        val name = edit("Назва пристрою", Build.MODEL)
        btn("Підключити") {
            p.edit().putString("server", server.text.toString().trim()).apply()
            Thread {
                try {
                    val r = Api.call(this, "POST", "/api/enroll",
                        JSONObject().put("code", code.text.toString().trim()).put("name", name.text.toString()), null)
                    p.edit().putString("token", r.getString("token")).apply()
                } catch (e: Exception) { runOnUiThread { Toast.makeText(this, e.message, Toast.LENGTH_LONG).show() } }
                runOnUiThread { refresh() }
            }.start()
        }
        btn("1. Доступ до статистики використання") { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        btn("2. Увімкнути службу спеціальних можливостей") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        btn("3. Адміністратор пристрою (захист від видалення)") {
            startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this, AdminReceiver::class.java)))
        }
        btn("4. Мережевий фільтр (для блокування сайтів)") {
            val intent = VpnService.prepare(this)
            if (intent != null) startActivityForResult(intent, VPN_REQUEST)
            else { Toast.makeText(this, "Вже дозволено", Toast.LENGTH_SHORT).show(); refresh() }
        }
        note("Пункт 4 — одноразовий системний запит; без нього блокування сайтів із панелі не матиме ефекту, навіть якщо його увімкнути.")
        btn("5. Геолокація (трекер) — натиснути двічі") {
            val fineGranted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!fineGranted) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), LOC_REQUEST)
            } else if (Build.VERSION.SDK_INT >= 29 &&
                checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), LOC_BG_REQUEST)
            } else {
                Toast.makeText(this, "Вже дозволено", Toast.LENGTH_SHORT).show()
            }
        }
        note("Перший раз дає точну геолокацію, другий — дозвіл працювати у фоні (без нього трекер не бачитиме розташування, коли додаток закритий).")
        btn("6. Вимкнути оптимізацію батареї для цього додатка") {
            val pm = getSystemService(PowerManager::class.java)
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            } else Toast.makeText(this, "Вже дозволено", Toast.LENGTH_SHORT).show()
        }
        note("Без пункту 6 Android може відкладати мережеву синхронізацію (команди, оновлення політики, трекер), поки екран вимкнений і пристрій лежить нерухомо (режим Doze) — керування тоді «наздоганяє» лише коли екран знову вмикають. Після пунктів 1–6 додаток можна приховати.")
        btn("7. Приховати додаток") {
            packageManager.setComponentEnabledSetting(
                ComponentName(this, "ua.parentcontrol.LauncherAlias"),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            Toast.makeText(this, "Приховано. Повернутись: наберіть *#*#7273#*#* в Телефоні, або відкрийте в браузері pcadmin://open", Toast.LENGTH_LONG).show()
            finish()
        }
        btn("Показати іконку знову") {
            packageManager.setComponentEnabledSetting(
                ComponentName(this, "ua.parentcontrol.LauncherAlias"),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            Toast.makeText(this, "Іконку повернуто", Toast.LENGTH_SHORT).show()
        }
        note("Способи відкрити це меню, коли іконку приховано: набрати *#*#7273#*#* у Телефоні, або відкрити посилання pcadmin://open у браузері.")
        status = TextView(this).also { l.addView(it) }
        setContentView(ScrollView(this).apply { addView(l) })
    }

    override fun onActivityResult(reqCode: Int, resCode: Int, data: Intent?) {
        super.onActivityResult(reqCode, resCode, data)
        if (reqCode == VPN_REQUEST) refresh()
    }

    override fun onRequestPermissionsResult(reqCode: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(reqCode, perms, results)
        refresh()
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val a11y = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.contains(packageName) == true
        val vpnOk = VpnService.prepare(this) == null
        val pm = getSystemService(PowerManager::class.java)
        val batteryOk = pm?.isIgnoringBatteryOptimizations(packageName) == true
        val fineLoc = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val bgLoc = Build.VERSION.SDK_INT < 29 || checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        fun ok(b: Boolean) = if (b) "✅" else "❌"
        status.text = "\n${ok(sp(this).getString("token", null) != null)} Підключено до сервера\n" +
            "${ok(UsageHelper.hasAccess(this))} Статистика\n${ok(a11y)} Служба блокування\n" +
            "${ok(dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java)))} Адміністратор пристрою\n" +
            "${ok(vpnOk)} Мережевий фільтр дозволено\n" +
            "${ok(batteryOk)} Без обмежень батареї (керування з вимкненим екраном)\n" +
            "${ok(fineLoc && bgLoc)} Геолокація (${if(!fineLoc) "не надано" else if(!bgLoc) "лише на передньому плані" else "дозволено завжди"})"
    }
}
