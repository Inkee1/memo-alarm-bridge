package io.github.inkee1.memoalarmbridge.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.inkee1.memoalarmbridge.core.*
import java.time.Instant

class AndroidScheduler(private val context:Context):SchedulerPort {
 private val alarms=context.getSystemService(AlarmManager::class.java)
 override fun canScheduleExact()=alarms.canScheduleExactAlarms()
 private fun intent(key:OccurrenceKey,flags:Int):PendingIntent? {
  val i=Intent("io.github.inkee1.memoalarmbridge.ALARM").setClassName(context,context.packageName+".alarm.AlarmReceiver").setData(Uri.Builder().scheme("memoalarm").authority("alarm").appendPath(key.token).build()).putExtra("token",key.token)
  return PendingIntent.getBroadcast(context,0,i,flags or PendingIntent.FLAG_IMMUTABLE)
 }
 override fun register(key:OccurrenceKey,at:Instant){
  check(canScheduleExact()){ "Exact alarm permission required" }
  val show=Intent().setClassName(context,context.packageName+".ui.MainActivity").setData(Uri.Builder().scheme("memoalarm").authority("show").appendPath(key.scheduleId).build())
  val showIntent=PendingIntent.getActivity(context,0,show,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  alarms.setAlarmClock(AlarmManager.AlarmClockInfo(at.toEpochMilli(),showIntent),requireNotNull(intent(key,PendingIntent.FLAG_UPDATE_CURRENT)))
 }
 override fun cancel(key:OccurrenceKey){intent(key,PendingIntent.FLAG_NO_CREATE)?.let{alarms.cancel(it);it.cancel()}}
}
