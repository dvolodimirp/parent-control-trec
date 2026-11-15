package ua.parentcontrol

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/** CameraManager.setTorchMode() needs no CAMERA permission — it was added in API 23
    specifically so apps could toggle the flash without opening a capture session.
    Returns a short human-readable outcome so failures aren't silently swallowed. */
object Flashlight {
    fun set(c: Context, on: Boolean): String {
        return try {
            val cm = c.getSystemService(CameraManager::class.java) ?: return "немає CameraManager"
            val id = cm.cameraIdList.firstOrNull { cid ->
                cm.getCameraCharacteristics(cid).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "на пристрої немає спалаху"
            cm.setTorchMode(id, on)
            if (on) "ліхтарик увімкнено" else "ліхтарик вимкнено"
        } catch (ex: Exception) {
            "помилка ліхтарика: ${ex.message ?: ex.javaClass.simpleName}"
        }
    }
}
