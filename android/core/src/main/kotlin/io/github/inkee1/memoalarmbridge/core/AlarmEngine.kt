package io.github.inkee1.memoalarmbridge.core

import java.time.Duration
import java.time.Instant
import java.util.UUID

class AlarmEngine(private val ledger:LedgerPort,private val scheduler:SchedulerPort,private val audio:AudioPort,private val now:()->Instant=Instant::now,private val unlocked:()->Boolean={true}) {
 private fun epochKey(scheduleId:String,epochId:String)=scheduleId+":"+epochId
 private fun cancelled(s:ExecutionLedger,scheduleId:String,epochId:String)=epochKey(scheduleId,epochId) in s.cancelledEpochs
 fun report(scheduleId:String):PrepareResult=synchronized(ledger){
  val s=ledger.read();val r=s.rules[scheduleId]?:return@synchronized PrepareResult("local_only")
  PrepareResult(r.state,r.active.mapNotNull(s.entries::get).filter{it.phase in listOf("active","firing")},r.candidate.mapNotNull(s.entries::get).filter{it.phase=="candidate"})
 }
 fun prepare(rule:ScheduleRule,version:Version):PrepareResult {
  validateRule(rule).requireValid();require(version.localGeneration>0&& (version.serverRevision==null||version.serverRevision>=0))
  if(!rule.enabled){cancel(rule.scheduleId,rule.epochId);return report(rule.scheduleId)}
  synchronized(ledger){
   val s=ledger.read();val old=s.rules[rule.scheduleId]
   if(cancelled(s,rule.scheduleId,rule.epochId))return PrepareResult("cancelled",report(rule.scheduleId).active)
   if(old!=null){
    if(version==old.desiredVersion&&rule.epochId==old.epochId)return report(rule.scheduleId)
    if(version.localGeneration<=old.desiredVersion.localGeneration||(old.desiredVersion.serverRevision!=null&&(version.serverRevision==null||version.serverRevision<old.desiredVersion.serverRevision)))return PrepareResult("conflict",report(rule.scheduleId).active)
   }
   val count=s.rules.values.count{it.scheduleId!=rule.scheduleId&&it.state !in listOf("cancelled","missed")&&!cancelled(s,it.scheduleId,it.epochId)}
   require(count<50){"At most 50 active schedules"}
   val state=when{!unlocked()->"blocked_audio";!scheduler.canScheduleExact()->"blocked_permission";else->"preparing"}
   val r=RuleExecution(rule.scheduleId,rule.epochId,version,state,old?.active?:emptyList())
   val obsolete=old?.candidate.orEmpty().mapNotNull(s.entries::get)
   ledger.write(s.copy(rules=s.rules+(rule.scheduleId to r),entries=s.entries+obsolete.associate{it.key.token to it.copy(phase="cancelled")}))
   obsolete.forEach{safeCancel(it.key)}
   if(state!="preparing")return report(rule.scheduleId)
  }
  val ref=UUID.nameUUIDFromBytes((rule.scheduleId+":"+rule.epochId+":"+version.serverRevision+":"+version.localGeneration).toByteArray(Charsets.UTF_8)).toString()
  try{audio.request(rule,ref){result->finishAudio(rule,version,result)}}catch(e:Exception){block(rule.scheduleId,version,"blocked_audio")}
  return report(rule.scheduleId)
 }
 private fun block(scheduleId:String,version:Version,state:String)=synchronized(ledger){val s=ledger.read();val r=s.rules[scheduleId]?:return@synchronized;if(r.desiredVersion!=version||cancelled(s,scheduleId,r.epochId))return@synchronized;ledger.write(s.copy(rules=s.rules+(scheduleId to r.copy(state=state))))}
 private fun verified(value:ReadyAudio):Boolean=validId(value.ref)&&validHash(value.sha256)&&value.bytes in 1..25L*1024*1024&&audio.inspect(value.ref)==value
 private fun finishAudio(rule:ScheduleRule,version:Version,outcome:Result<ReadyAudio>)=synchronized(ledger){
  var s=ledger.read();var r=s.rules[rule.scheduleId]?:return@synchronized
  if(r.desiredVersion!=version||r.epochId!=rule.epochId||r.state!="preparing"||cancelled(s,rule.scheduleId,rule.epochId))return@synchronized
  val ready=outcome.getOrNull()
  if(ready==null||!runCatching{verified(ready)}.getOrDefault(false)){block(rule.scheduleId,version,"blocked_audio");return@synchronized}
  if(!scheduler.canScheduleExact()){block(rule.scheduleId,version,"blocked_permission");return@synchronized}
  val occurrences=nextOccurrences(rule,now(),2)
  if(occurrences.isEmpty()){block(rule.scheduleId,version,"missed");return@synchronized}
  val candidates=occurrences.map{AlarmEntry(OccurrenceKey(it.id,rule.scheduleId,rule.epochId,version),it.atUtc,"candidate",ready.ref)}
  r=r.copy(candidate=candidates.map{it.key.token})
  ledger.write(s.copy(rules=s.rules+(rule.scheduleId to r),entries=s.entries+candidates.associateBy{it.key.token}))
  try {
   // Candidate intent is durable before OS calls. A process death here can be
   // recovered from the matching delivered PendingIntent without false ACKs.
   candidates.forEach{scheduler.register(it.key,Instant.parse(it.atUtc))}
   s=ledger.read();r=s.rules.getValue(rule.scheduleId)
   val old=r.active.mapNotNull(s.entries::get)
   val committed=s.entries+old.associate{it.key.token to it.copy(phase="cancelled")}+candidates.associate{it.key.token to it.copy(phase="active")}
   ledger.write(s.copy(rules=s.rules+(rule.scheduleId to r.copy(state="armed",active=candidates.map{it.key.token},candidate=emptyList())),entries=committed))
   old.forEach{safeCancel(it.key)}
  }catch(e:Exception){
   candidates.forEach{safeCancel(it.key)};s=ledger.read();r=s.rules.getValue(rule.scheduleId)
   ledger.write(s.copy(rules=s.rules+(rule.scheduleId to r.copy(state="blocked_permission",candidate=emptyList())),entries=s.entries+candidates.associate{it.key.token to it.copy(phase="cancelled")}))
  }
 }
 fun cancel(scheduleId:String,epochId:String):CancelResult=synchronized(ledger){
  require(validId(scheduleId)&&validId(epochId));val s=ledger.read();val affected=s.entries.values.filter{it.key.scheduleId==scheduleId&&it.key.epochId==epochId}
  val tokens=affected.map{it.key.token}.toSet();val r=s.rules[scheduleId];val rules=if(r==null)s.rules else s.rules+(scheduleId to r.copy(state=if(r.epochId==epochId)"cancelled" else r.state,active=r.active.filterNot{it in tokens},candidate=r.candidate.filterNot{it in tokens}))
  ledger.write(s.copy(rules=rules,entries=s.entries+affected.associate{it.key.token to it.copy(phase="cancelled")},cancelledEpochs=s.cancelledEpochs+epochKey(scheduleId,epochId)))
  affected.forEach{safeCancel(it.key)};CancelResult()
 }
 private fun safeCancel(key:OccurrenceKey){try{scheduler.cancel(key)}catch(_:Exception){/* Durable tombstones and version guards remain authoritative. */}}
 fun onTrigger(key:OccurrenceKey):TriggerDecision=onTrigger(key.token)
 fun onTrigger(token:String):TriggerDecision=synchronized(ledger){
  var s=ledger.read();val entry=s.entries[token]?:return@synchronized TriggerDecision(false,"unknown")
  var r=s.rules[entry.key.scheduleId]?:return@synchronized TriggerDecision(false,"unknown")
  if(cancelled(s,entry.key.scheduleId,entry.key.epochId)||entry.phase !in listOf("active","candidate"))return@synchronized TriggerDecision(false,"cancelled, stale or already claimed")
  val candidate=entry.phase=="candidate"&&token in r.candidate&&entry.key.version==r.desiredVersion&&entry.key.epochId==r.epochId
  if(!candidate&&(entry.phase!="active"||token !in r.active))return@synchronized TriggerDecision(false,"stale")
  val at=Instant.parse(entry.atUtc);val current=now()
  if(current.isBefore(at.minusSeconds(2)))return@synchronized TriggerDecision(false,"early")
  if(Duration.between(at,current)>Duration.ofMinutes(5)){
   ledger.write(s.copy(entries=s.entries+(token to entry.copy(phase="missed")),rules=s.rules+(r.scheduleId to r.copy(state="missed"))))
   return@synchronized TriggerDecision(false,"missed")
  }
  val privateAvailable=unlocked()
  if(privateAvailable&&!runCatching{audio.inspect(entry.audioRef)?.let{validHash(it.sha256)&&it.bytes in 1..25L*1024*1024}?:false}.getOrDefault(false)){
   ledger.write(s.copy(entries=s.entries+(token to entry.copy(phase="blocked_audio")),rules=s.rules+(r.scheduleId to r.copy(state="blocked_audio"))))
   return@synchronized TriggerDecision(false,"audio unavailable")
  }
  if(candidate){
   // This OS delivery proves registration only for this candidate, never for
   // its unconfirmed siblings. Recovery later replenishes future occurrences.
   val old=r.active.mapNotNull(s.entries::get);s=s.copy(entries=s.entries+old.associate{it.key.token to it.copy(phase="cancelled")});r=r.copy(active=listOf(token),candidate=r.candidate.filterNot{it==token})
   ledger.write(s.copy(rules=s.rules+(r.scheduleId to r)));old.forEach{safeCancel(it.key)}
  }
  ledger.write(s.copy(entries=s.entries+(token to entry.copy(phase="firing")),rules=s.rules+(r.scheduleId to r.copy(state=if(entry.key.version==r.desiredVersion)"fired" else r.state))))
  TriggerDecision(true,"claimed",entry.key,if(privateAvailable)entry.audioRef else null,!privateAvailable)
 }
 fun canPlay(token:String):Boolean=synchronized(ledger){val s=ledger.read();val e=s.entries[token]?:return@synchronized false;val r=s.rules[e.key.scheduleId]?:return@synchronized false;e.phase=="firing"&&token in r.active&&!cancelled(s,e.key.scheduleId,e.key.epochId)}
}
