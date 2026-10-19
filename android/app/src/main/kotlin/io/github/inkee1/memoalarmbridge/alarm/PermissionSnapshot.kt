package io.github.inkee1.memoalarmbridge.alarm

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.UserManager

data class PermissionSnapshot(val values:Map<String,String>) {
    companion object {
        fun read(context:Context):PermissionSnapshot {
            fun checked(read:()->Boolean)=runCatching{if(read())"granted" else "denied"}.getOrDefault("unknown")
            return PermissionSnapshot(mapOf(
                "exact_alarm" to checked{context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()},
                "notifications" to checked{context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()},
                "alarm_volume" to checked{context.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_ALARM)>0},
                "private_storage" to checked{context.getSystemService(UserManager::class.java).isUserUnlocked},
                // A setting alone is not evidence that synthesis completed.
                "offline_voice" to "unknown"
            ))
        }
    }
}
