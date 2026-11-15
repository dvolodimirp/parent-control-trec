package ua.parentcontrol

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import kotlin.concurrent.thread

/** "Find my phone" style loud alarm for ~25s, even if the device is on silent.
    sound() reports an immediate outcome (started / nothing to play / error);
    failures that happen later, once playback is already running, are swallowed
    since by then the command has visibly done something. */
object Alarm {
    fun sound(c: Context): String {
        val am = c.getSystemService(AudioManager::class.java)
        val uri = try {
            RingtoneManager.getActualDefaultRingtoneUri(c, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getValidRingtoneUri(c)
        } catch (ex: Exception) { null }
        if (uri == null) return "немає звуку сигналу на пристрої"
        thread {
            var player: MediaPlayer? = null
            val prevVol = am?.getStreamVolume(AudioManager.STREAM_ALARM) ?: 0
            try {
                val maxVol = am?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: 0
                am?.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
                player = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    setDataSource(c, uri)
                    isLooping = true
                    prepare(); start()
                }
                Thread.sleep(25_000)
            } catch (_: Exception) {
            } finally {
                try { player?.stop(); player?.release() } catch (_: Exception) {}
                try { am?.setStreamVolume(AudioManager.STREAM_ALARM, prevVol, 0) } catch (_: Exception) {}
            }
        }
        return "сигнал відтворюється"
    }
}
