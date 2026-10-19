package io.github.inkee1.memoalarmbridge.core

import java.time.Duration
import java.time.Instant
import java.util.UUID

class AlarmEngine(private val ledger:LedgerPort,private val scheduler:SchedulerPort,private val audio:AudioPort,private val now:()->Instant=Instant::now,private val unlocked:()->Boolean={true},private val ruleProvider:()->List<Pair<ScheduleRule,Version>> = {emptyList()}) {
 private val owner=UUID.randomUUID().toString()
 private val pendingAudio=mutableSetOf<String>()
 private fun epochKey(scheduleId:String,epochId:String)=scheduleId+":"+epochId
 private fun cancelled(s:ExecutionLedger,scheduleId:String,epochId:String)=epochKey(scheduleId,epochId) in s.cancelledEpochs
 fun report(scheduleId:String):PrepareResult=synchronized(ledger){
  val s=ledger.read();val r=s.rules[scheduleId]?:return@synchronized PrepareResult("local_only")
  PrepareResult(r.state,r.active.mapNotNull(s.entries::get).filter{it.phase in listOf("active","firing")},r.candidate.mapNotNull(s.entries::get).filter{it.phase=="candidate"})
 }
 fun prepare(rule:ScheduleRule,version:Version):PrepareResult=prepareInternal(rule,version,false)
 private fun prepareInternal(rule:ScheduleRule,version:Version,refresh:Boolean):PrepareResult {
  validateRule(rule).requireValid();require(version.localGeneration>0&& (version.serverRevision==null||version.serverRevision>=0))
  if(!rule.enabled){cancel(rule.scheduleId,rule.epochId);return report(rule.scheduleId)}
  synchronized(ledger){
   val s=ledger.read();val old=s.rules[rule.scheduleId]
   if(cancelled(s,rule.scheduleId,rule.epochId))return PrepareResult("cancelled",report(rule.scheduleId).active)
   if(old!=null){
    if(version==old.desiredVersion&&rule.epochId==old.epochId&&(!refresh||audioRef(rule,version) in pendingAudio))return report(rule.scheduleId)
    if(version.localGeneration<old.desiredVersion.localGeneration||(version.localGeneration==old.desiredVersion.localGeneration&&version!=old.desiredVersion))return PrepareResult("conflict",report(rule.scheduleId).active)
   }
   val count=s.rules.values.count{it.scheduleId!=rule.scheduleId&&!cancelled(s,it.scheduleId,it.epochId)&&(it.state !in listOf("cancelled","missed","fired","dismissed","interrupted")||(it.active+it.candidate).mapNotNull(s.entries::get).any{e->e.phase in listOf("active","candidate","firing")})}
   val future=nextOccurrences(rule,now(),1).isNotEmpty()
   require(!future||count<50){"At most 50 active schedules"}
   val state=when{!future->"missed";!unlocked()->"blocked_audio";!scheduler.canScheduleExact()->"blocked_permission";else->"preparing"}
   val r=RuleExecution(rule.scheduleId,rule.epochId,version,state,if(future)old?.active?:emptyList()else emptyList())
   val obsolete=(old?.candidate.orEmpty()+if(future)emptyList()else old?.active.orEmpty()).mapNotNull(s.entries::get)
   ledger.write(s.copy(rules=s.rules+(rule.scheduleId to r),entries=s.entries+obsolete.associate{it.key.token to it.copy(phase="cancelled")}))
   obsolete.forEach{safeCancel(it.key)}
   if(state!="preparing")return report(rule.scheduleId)
  }
  val ref=audioRef(rule,version)
  if(refresh){val existing=runCatching{audio.inspect(ref)}.getOrNull();if(existing!=null){finishAudio(rule,version,Result.success(existing));return report(rule.scheduleId)}}
  synchronized(ledger){pendingAudio+=ref}
  try{audio.request(rule,ref){result->try{finishAudio(rule,version,result)}finally{synchronized(ledger){pendingAudio-=ref}}}}catch(e:Exception){synchronized(ledger){pendingAudio-=ref};block(rule.scheduleId,version,"blocked_audio")}
  return report(rule.scheduleId)
 }
 private fun audioRef(rule:ScheduleRule,version:Version)=UUID.nameUUIDFromBytes((rule.scheduleId+":"+rule.epochId+":"+version.serverRevision+":"+version.localGeneration).toByteArray(Charsets.UTF_8)).toString()
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
  val candidates=occurrences.map{AlarmEntry(OccurrenceKey(it.id,rule.scheduleId,rule.epochId,version),it.atUtc,"candidate",ready.ref,respectSilent=rule.respectSilent)}
  r=r.copy(candidate=candidates.map{it.key.token})
  ledger.write(s.copy(rules=s.rules+(rule.scheduleId to r),entries=s.entries+candidates.associateBy{it.key.token}))
  try {
   // Candidate intent is durable before OS calls. A process death here can be
   // recovered from the matching delivered PendingIntent without false ACKs.
   candidates.forEach{scheduler.register(it.key,Instant.parse(it.atUtc))}
   s=ledger.read();r=s.rules.getValue(rule.scheduleId)
   val nextTokens=candidates.map{it.key.token}.toSet()
   val preserved=r.active.mapNotNull(s.entries::get).filter{it.key.version==version&&it.key.epochId==rule.epochId&&((it.phase=="firing"&&it.claimOwner==owner)||(it.phase=="active"&&(it.kind=="snooze"||recentlyDue(it)) ))&&it.key.token !in nextTokens}
   val old=r.active.mapNotNull(s.entries::get).filter{it.key.token !in nextTokens&&it !in preserved}
   val committed=s.entries+old.associate{it.key.token to it.copy(phase="cancelled")}+candidates.associate{it.key.token to it.copy(phase="active")}
   ledger.write(s.copy(rules=s.rules+(rule.scheduleId to r.copy(state="armed",active=preserved.map{it.key.token}+candidates.map{it.key.token},candidate=emptyList())),entries=committed))
   old.forEach{safeCancel(it.key)}
  }catch(e:Exception){
   candidates.forEach{safeCancel(it.key)};s=ledger.read();r=s.rules.getValue(rule.scheduleId)
   ledger.write(s.copy(rules=s.rules+(rule.scheduleId to r.copy(state="blocked_permission",candidate=emptyList())),entries=s.entries+candidates.associate{it.key.token to it.copy(phase="cancelled")}))
  }
 }
 private fun recentlyDue(e:AlarmEntry):Boolean {val at=Instant.parse(e.atUtc);return !at.isAfter(now())&&Duration.between(at,now())<=Duration.ofMinutes(5)}
 private fun validCandidate(s:ExecutionLedger,r:RuleExecution,e:AlarmEntry):Boolean {
  if(e.key.token !in r.candidate)return false
  if(e.kind=="snooze")return s.entries[e.parentToken]?.phase=="snoozed"&&r.active.mapNotNull(s.entries::get).none{it.key.version.localGeneration>e.key.version.localGeneration}
  return e.key.version==r.desiredVersion&&e.key.epochId==r.epochId
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
  val candidate=entry.phase=="candidate"&&validCandidate(s,r,entry)
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
   val old=if(entry.kind=="snooze")emptyList() else r.active.mapNotNull(s.entries::get);s=s.copy(entries=s.entries+old.associate{it.key.token to it.copy(phase="cancelled")});r=r.copy(active=(if(entry.kind=="snooze")r.active else emptyList())+token,candidate=r.candidate.filterNot{it==token})
   ledger.write(s.copy(rules=s.rules+(r.scheduleId to r)));old.forEach{safeCancel(it.key)}
  }
  ledger.write(s.copy(entries=s.entries+(token to entry.copy(phase="firing",claimOwner=owner)),rules=s.rules+(r.scheduleId to r.copy(state=if(entry.key.version==r.desiredVersion)"fired" else r.state))))
  safeCancel(entry.key)
  TriggerDecision(true,"claimed",entry.key,if(privateAvailable)entry.audioRef else null,!privateAvailable)
 }
 fun canPlay(token:String):Boolean=synchronized(ledger){val s=ledger.read();val e=s.entries[token]?:return@synchronized false;val r=s.rules[e.key.scheduleId]?:return@synchronized false;e.phase=="firing"&&e.claimOwner==owner&&token in r.active&&!cancelled(s,e.key.scheduleId,e.key.epochId)}
 fun withPlaybackAuthority(token:String,start:()->Unit):Boolean=synchronized(ledger){if(!canPlay(token))false else {start();true}}
 fun finish(token:String,state:String)=synchronized(ledger){
  require(state in listOf("fired","dismissed","interrupted"));val s=ledger.read();val e=s.entries[token]?:return@synchronized
  if(e.phase!="firing"||e.claimOwner!=owner)return@synchronized;val r=s.rules[e.key.scheduleId]?:return@synchronized
  val remaining=r.active.filterNot{it==token};val nextState=if(e.key.version!=r.desiredVersion)r.state else if(remaining.mapNotNull(s.entries::get).any{it.phase=="active"&&Instant.parse(it.atUtc)>now()})"armed" else state
  ledger.write(s.copy(entries=s.entries+(token to e.copy(phase=state)),rules=s.rules+(r.scheduleId to r.copy(state=nextState,active=remaining))))
 }
 fun snooze(token:String,minutes:Int=5):PrepareResult=synchronized(ledger){
  require(minutes==5){"Snooze is five minutes"};val s=ledger.read();val e=s.entries[token]?:return@synchronized PrepareResult("cancelled");val r=s.rules[e.key.scheduleId]?:return@synchronized PrepareResult("cancelled")
  if(!canPlay(token))return@synchronized report(r.scheduleId)
  val at=now().plusSeconds(300);val id=UUID.nameUUIDFromBytes((token+":snooze:"+at).toByteArray(Charsets.UTF_8)).toString();val key=e.key.copy(occurrenceId=id)
  val next=AlarmEntry(key,at.toString(),"candidate",e.audioRef,kind="snooze",parentToken=token,respectSilent=e.respectSilent)
  ledger.write(s.copy(entries=s.entries+(token to e.copy(phase="snoozed"))+(key.token to next),rules=s.rules+(r.scheduleId to r.copy(active=r.active.filterNot{it==token},candidate=r.candidate+key.token))))
  try{check(scheduler.canScheduleExact());scheduler.register(key,at);val fresh=ledger.read();val rr=fresh.rules.getValue(r.scheduleId);ledger.write(fresh.copy(entries=fresh.entries+(key.token to next.copy(phase="active")),rules=fresh.rules+(r.scheduleId to rr.copy(state=if(e.key.version==rr.desiredVersion)"snoozed" else rr.state,active=rr.active+key.token,candidate=rr.candidate.filterNot{it==key.token}))))}catch(_:Exception){safeCancel(key);val fresh=ledger.read();val rr=fresh.rules.getValue(r.scheduleId);ledger.write(fresh.copy(entries=fresh.entries+(key.token to next.copy(phase="cancelled")),rules=fresh.rules+(r.scheduleId to rr.copy(state="blocked_permission",candidate=rr.candidate.filterNot{it==key.token}))))}
  report(r.scheduleId)
 }
 fun reconcile(reason:RecoveryReason,at:Instant=now()):RecoveryReport {
  var registered=0;var missed=0;var interrupted=0
  synchronized(ledger){
   var s=ledger.read();val entries=s.entries.toMutableMap();val rules=s.rules.toMutableMap()
   for(e in s.entries.values){
    if(e.phase=="firing"&&e.claimOwner!=owner){entries[e.key.token]=e.copy(phase="interrupted");rules[e.key.scheduleId]?.let{r->if(r.desiredVersion==e.key.version)rules[r.scheduleId]=r.copy(state=if(r.active.mapNotNull(s.entries::get).any{it.phase=="active"&&Instant.parse(it.atUtc)>at})"armed" else "interrupted")};interrupted++;continue}
    if(e.phase !in listOf("active","candidate"))continue
    if(cancelled(s,e.key.scheduleId,e.key.epochId)){entries[e.key.token]=e.copy(phase="cancelled");safeCancel(e.key);continue}
    val time=Instant.parse(e.atUtc)
    if(!time.isAfter(at)){
     if(reason in listOf(RecoveryReason.BOOT,RecoveryReason.LOCKED_BOOT,RecoveryReason.PACKAGE_REPLACED)||Duration.between(time,at)>Duration.ofMinutes(5)){entries[e.key.token]=e.copy(phase="missed");missed++;safeCancel(e.key)}
     continue
    }
    if(e.phase=="active")try{check(scheduler.canScheduleExact());scheduler.register(e.key,time);registered++}catch(_:Exception){rules[e.key.scheduleId]?.let{rules[e.key.scheduleId]=it.copy(state="blocked_permission")}}
   }
   rules.entries.toList().forEach{(id,r)->val active=r.active.filter{entries[it]?.phase in listOf("active","firing")};val candidates=r.candidate.filter{entries[it]?.phase=="candidate"};rules[id]=r.copy(active=active,candidate=candidates)}
   s=s.copy(entries=entries,rules=rules);ledger.write(s)
   // Recover complete future candidate groups using already durable audio intent.
   for(r in s.rules.values){val candidates=r.candidate.mapNotNull(s.entries::get).filter{Instant.parse(it.atUtc)>at&&!cancelled(s,it.key.scheduleId,it.key.epochId)&&validCandidate(s,r,it)};if(candidates.isEmpty())continue
    try{check(scheduler.canScheduleExact());candidates.forEach{scheduler.register(it.key,Instant.parse(it.atUtc));registered++};val fresh=ledger.read();val rr=fresh.rules.getValue(r.scheduleId);val regular=candidates.any{it.kind=="regular"};val old=if(regular)rr.active.mapNotNull(fresh.entries::get).filter{it.key.version!=r.desiredVersion}else emptyList();val tokens=candidates.map{it.key.token};ledger.write(fresh.copy(entries=fresh.entries+old.associate{it.key.token to it.copy(phase="cancelled")}+candidates.associate{it.key.token to it.copy(phase="active")},rules=fresh.rules+(r.scheduleId to rr.copy(state="armed",active=(rr.active-old.map{it.key.token}.toSet()+tokens).distinct(),candidate=rr.candidate-tokens.toSet()))));old.forEach{safeCancel(it.key)}}catch(_:Exception){candidates.forEach{safeCancel(it.key)};block(r.scheduleId,r.desiredVersion,"blocked_permission")}
   }
  }
  val privateRead=unlocked()
  if(privateRead){for((rule,version) in ruleProvider()){if(!rule.enabled){cancel(rule.scheduleId,rule.epochId);continue};if(nextOccurrences(rule,at,2).isNotEmpty())prepareInternal(rule,version,true)}}
  return RecoveryReport(registered,missed,interrupted,privateRead)
 }
}
