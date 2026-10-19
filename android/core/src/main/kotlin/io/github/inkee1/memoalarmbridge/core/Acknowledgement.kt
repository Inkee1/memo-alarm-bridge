package io.github.inkee1.memoalarmbridge.core

import java.time.Instant

fun buildDeviceAck(deviceId:String,rule:ScheduleRule,version:Version,report:PrepareResult,cancelled:Boolean,capabilities:Map<String,String>,audioHash:(String)->String?,now:Instant,synced:Boolean=true,serverGenerationFloor:Long=1):DeviceAck? {
    val revision=version.serverRevision?:return null
    require(revision>0&&version.localGeneration>0&&validId(deviceId))
    val exact=capabilities["exact_alarm"]=="granted"
    val future=report.active.filter{it.phase=="active"&&Instant.parse(it.atUtc)>now}
    val matching=if(synced&&exact)future.filter{it.key.version==version&&it.key.epochId==rule.epochId}else emptyList()
    fun occurrence(entry:AlarmEntry):Occurrence?=audioHash(entry.audioRef)?.takeIf(::validHash)?.let{Occurrence(entry.key.occurrenceId,entry.atUtc,it)}
    val ready=matching.mapNotNull(::occurrence)
    val state=when{
        !rule.enabled->if(cancelled)"cancelled" else "cancel_pending"
        cancelled->"cancelled"
        !synced->"pending_delivery"
        !exact->"blocked_permission"
        ready.size<matching.size->"blocked_audio"
        report.state=="armed"&&ready.isEmpty()->"pending_delivery"
        report.state=="local_only"->"pending_delivery"
        else->report.state
    }
    val previous=if(exact)future.filter{it.key.version!=version||it.key.epochId!=rule.epochId}.groupBy{it.key.epochId to it.key.version}.mapNotNull{(key,entries)->val occurrences=entries.mapNotNull(::occurrence);if(occurrences.isEmpty())null else AppliedEvidence(if(key.second.localGeneration<serverGenerationFloor)0 else key.second.serverRevision?:0,key.second.localGeneration,key.first,occurrences)}else emptyList()
    val caps=capabilities+mapOf("offline_voice" to if(ready.isNotEmpty())"granted"else if(state=="blocked_audio")"denied"else "unknown")
    return DeviceAck(deviceId,rule.scheduleId,rule.epochId,revision,version.localGeneration,state,if(state=="cancelled")emptyList()else ready,now.toString(),caps,previous)
}
