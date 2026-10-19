package io.github.inkee1.memoalarmbridge.core

import java.time.Instant

data class OccurrenceKey(val occurrenceId:String,val scheduleId:String,val epochId:String,val version:Version) {
 val token:String get()=occurrenceId+":"+(version.serverRevision?.toString()?:"local")+":"+version.localGeneration
}
// This ledger contains execution metadata only. Never add speech, note content,
// authentication secrets, or private audio bytes to these device-protected types.
data class AlarmEntry(val key:OccurrenceKey,val atUtc:String,val phase:String,val audioRef:String)
data class RuleExecution(val scheduleId:String,val epochId:String,val desiredVersion:Version,val state:String,val active:List<String> = emptyList(),val candidate:List<String> = emptyList())
data class ExecutionLedger(val rules:Map<String,RuleExecution> = emptyMap(),val entries:Map<String,AlarmEntry> = emptyMap(),val cancelledEpochs:Set<String> = emptySet())
data class ReadyAudio(val ref:String,val sha256:String,val bytes:Long)
data class PrepareResult(val state:String,val active:List<AlarmEntry> = emptyList(),val candidate:List<AlarmEntry> = emptyList())
data class CancelResult(val state:String="cancelled")
data class TriggerDecision(val play:Boolean,val reason:String,val key:OccurrenceKey?=null,val audioRef:String?=null,val fallbackTone:Boolean=false)
interface LedgerPort {fun read():ExecutionLedger;fun write(value:ExecutionLedger)}
interface SchedulerPort {fun canScheduleExact():Boolean;fun register(key:OccurrenceKey,at:Instant);fun cancel(key:OccurrenceKey)}
interface AudioPort {
 // Returning from request is only queue acceptance. Success is delivered after
 // completion, atomic file placement, and a durable matching metadata record.
 fun request(rule:ScheduleRule,ref:String,done:(Result<ReadyAudio>)->Unit)
 fun inspect(ref:String):ReadyAudio?
}
