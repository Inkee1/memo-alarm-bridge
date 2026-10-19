package io.github.inkee1.memoalarmbridge.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.inkee1.memoalarmbridge.AppGraph
import io.github.inkee1.memoalarmbridge.core.RecoveryReason

class AlarmReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        val token=intent.getStringExtra("token")?:return
        val pending=goAsync();val graph=AppGraph.get(context)
        graph.triggers.execute {
            try {
                val decision=graph.engine.onTrigger(token)
                if(decision.play) {
                    try {context.startForegroundService(Intent(context,AlarmPlaybackService::class.java).putExtra("token",token))}
                    catch(_:Exception){graph.engine.finish(token,"interrupted")}
                }
                graph.work.execute {runCatching{graph.engine.reconcile(RecoveryReason.TRIGGER)};graph.requestSync()}
            } finally {graph.requestSync();pending.finish()}
        }
    }
}

class AlarmActions:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        val token=intent.getStringExtra("token")?:return
        val pending=goAsync();val graph=AppGraph.get(context)
        graph.triggers.execute {
            try {
                if(intent.action=="io.github.inkee1.memoalarmbridge.SNOOZE")graph.engine.snooze(token)
                else if(intent.action=="io.github.inkee1.memoalarmbridge.STOP")graph.engine.finish(token,"dismissed")
            } finally {graph.requestSync();pending.finish()}
        }
    }
}

class RecoveryReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        val reason=when(intent.action){
            Intent.ACTION_LOCKED_BOOT_COMPLETED->RecoveryReason.LOCKED_BOOT
            Intent.ACTION_BOOT_COMPLETED->RecoveryReason.BOOT
            Intent.ACTION_USER_UNLOCKED->RecoveryReason.UNLOCK
            Intent.ACTION_MY_PACKAGE_REPLACED->RecoveryReason.PACKAGE_REPLACED
            Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED->RecoveryReason.TIME_CHANGED
            android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED->RecoveryReason.PERMISSION_CHANGED
            else->return
        }
        val pending=goAsync();val graph=AppGraph.get(context)
        graph.triggers.execute {try{graph.engine.reconcile(reason)}finally{graph.requestSync();pending.finish()}}
    }
}
