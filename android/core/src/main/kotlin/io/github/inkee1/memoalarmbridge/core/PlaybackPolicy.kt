package io.github.inkee1.memoalarmbridge.core

enum class DndMode { ALL,ALARMS,PRIORITY,NONE,UNKNOWN }
object PlaybackPolicy {
 // PRIORITY/UNKNOWN are still governed by Android's USAGE_ALARM policy.
 // This never authorizes changing volume or bypassing DND.
 fun allowed(alarmVolume:Int,silentMode:Boolean,respectSilent:Boolean,dnd:DndMode):Boolean=alarmVolume>0&&!(respectSilent&&silentMode)&&dnd!=DndMode.NONE
}
class PlaybackQueue {
 private val queue=mutableListOf<String>()
 val active:String? get()=queue.firstOrNull()
 val tokens:List<String> get()=queue.toList()
 fun offer(token:String){if(token !in queue)queue+=token}
 fun remove(token:String):Boolean {val wasActive=active==token;queue.remove(token);return wasActive}
 fun clear(){queue.clear()}
}
