package io.github.inkee1.memoalarmbridge.core

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class AlarmEngineTest {
 private val now=Instant.parse("2030-01-01T08:00:00Z")
 private val rule=ScheduleRule(scheduleId="11111111-1111-4111-8111-111111111111",epochId="22222222-2222-4222-8222-222222222222",atUtc="2030-01-01T09:00:00Z",body="Synthetic reminder")
 private class MemoryLedger:LedgerPort {var value=ExecutionLedger();override fun read()=value;override fun write(value:ExecutionLedger){this.value=value}}
 private class Crash:Error()
 private class Scheduler:SchedulerPort {
  var permitted=true;var crash=false;var fail=false;val scheduled=mutableMapOf<String,Instant>()
  override fun canScheduleExact()=permitted
  override fun register(key:OccurrenceKey,at:Instant){if(fail)throw SecurityException("test permission lost");scheduled[key.token]=at;if(crash)throw Crash()}
  override fun cancel(key:OccurrenceKey){scheduled.remove(key.token)}
 }
 private class Audio:AudioPort {
  var auto=true;var fail=false;var mismatch=false;var forbidRead=false
  val files=mutableMapOf<String,ReadyAudio>();var pending:(()->Unit)?=null
  override fun request(rule:ScheduleRule,ref:String,done:(Result<ReadyAudio>)->Unit){
   pending={if(fail)done(Result.failure(IllegalStateException("test synthesis failure")))else{val audio=ReadyAudio(ref,"a".repeat(64),1000);files[ref]=audio.copy(sha256=if(mismatch)"b".repeat(64)else audio.sha256);done(Result.success(audio))}}
   if(auto)pending!!.invoke()
  }
  override fun inspect(ref:String):ReadyAudio?{check(!forbidRead){"credential-protected storage accessed while locked"};return files[ref]}
 }
 private class Fixture(val at:Instant){val ledger=MemoryLedger();val scheduler=Scheduler();val audio=Audio();var now=at;var unlocked=true;fun engine()=AlarmEngine(ledger,scheduler,audio,{now},{unlocked})}
 @Test fun permissionDeniedNeverArms(){val f=Fixture(now);f.scheduler.permitted=false;val r=f.engine().prepare(rule,Version(1,1));assertEquals("blocked_permission",r.state);assertTrue(f.scheduler.scheduled.isEmpty());assertTrue(r.active.isEmpty())}
 @Test fun synthesisQueueSuccessIsNotReady(){val f=Fixture(now);f.audio.auto=false;val e=f.engine();assertEquals("preparing",e.prepare(rule,Version(1,1)).state);assertTrue(f.scheduler.scheduled.isEmpty());f.audio.pending!!.invoke();assertEquals("armed",e.report(rule.scheduleId).state);assertEquals(1,f.scheduler.scheduled.size)}
 @Test fun audioHashMismatchBlocks(){val f=Fixture(now);f.audio.mismatch=true;val r=f.engine().prepare(rule,Version(1,1));assertEquals("blocked_audio",r.state);assertTrue(f.scheduler.scheduled.isEmpty())}
 @Test fun crashAfterOsRegisterRecovers(){val f=Fixture(now);f.scheduler.crash=true;try{f.engine().prepare(rule,Version(1,1));fail("crash not injected")}catch(_:Crash){};assertEquals(1,f.scheduler.scheduled.size);val key=f.ledger.value.entries.values.single().key;f.now=Instant.parse(rule.atUtc);val recovered=f.engine().onTrigger(key);assertTrue(recovered.play);assertFalse(f.engine().onTrigger(key).play)}
 @Test fun recentlyDueRecurringCandidateSurvivesReconcileBeforeDelivery(){
  for(reason in listOf(RecoveryReason.APP_OPEN,RecoveryReason.TIME_CHANGED)){
   val f=Fixture(now);val recurring=rule.copy(kind="daily",atUtc="",localTime="09:00",zoneId="UTC");val version=Version(1,1)
   f.scheduler.crash=true;try{f.engine().prepare(recurring,version);fail("crash not injected")}catch(_:Crash){}
   val due=f.ledger.value.entries.values.minBy{Instant.parse(it.atUtc)}.key
   f.scheduler.crash=false;f.now=Instant.parse("2030-01-01T09:01:00Z")
   val recovered=AlarmEngine(f.ledger,f.scheduler,f.audio,{f.now},{true},{listOf(recurring to version)})
   recovered.reconcile(reason)
   assertTrue("due registration was cancelled by $reason replenishment",due.token in f.scheduler.scheduled)
   assertTrue("valid OS delivery rejected after $reason",recovered.onTrigger(due).play)
   assertFalse(recovered.onTrigger(due).play)
  }
 }
 @Test fun staleRevisionCannotPlay(){val f=Fixture(now);val e=f.engine();val old=e.prepare(rule,Version(1,1)).active.single().key;val fresh=e.prepare(rule.copy(body="Updated synthetic reminder"),Version(2,2)).active.single().key;f.now=Instant.parse(rule.atUtc);assertFalse(e.onTrigger(old).play);assertTrue(e.onTrigger(fresh).play)}
 @Test fun cancelWinsBeforeDispatch(){val f=Fixture(now);val e=f.engine();val key=e.prepare(rule,Version(1,1)).active.single().key;e.cancel(rule.scheduleId,rule.epochId);f.now=Instant.parse(rule.atUtc);assertFalse(e.onTrigger(key).play);assertTrue(f.scheduler.scheduled.isEmpty());assertEquals("cancelled",e.prepare(rule,Version(2,2)).state)}
 @Test fun cancelWinsWhileSynthesisIsPending(){val f=Fixture(now);f.audio.auto=false;val e=f.engine();e.prepare(rule,Version(1,1));e.cancel(rule.scheduleId,rule.epochId);f.audio.pending!!.invoke();assertTrue(f.scheduler.scheduled.isEmpty());assertEquals("cancelled",e.report(rule.scheduleId).state)}
 @Test fun replacementFailureKeepsOldActive(){val f=Fixture(now);val e=f.engine();val old=e.prepare(rule,Version(1,1)).active.single().key;f.audio.fail=true;val result=e.prepare(rule.copy(body="Failed candidate"),Version(2,2));assertEquals("blocked_audio",result.state);assertEquals(old,result.active.single().key);f.now=Instant.parse(rule.atUtc);assertTrue(e.onTrigger(old).play)}
 @Test fun replacementRegistrationFailureKeepsOldActive(){val f=Fixture(now);val e=f.engine();val old=e.prepare(rule,Version(1,1)).active.single().key;f.scheduler.fail=true;val result=e.prepare(rule.copy(body="Failed candidate"),Version(2,2));assertEquals("blocked_permission",result.state);assertEquals(old,result.active.single().key);f.now=Instant.parse(rule.atUtc);assertTrue(e.onTrigger(old).play)}
 @Test fun lockedTriggerNeverReadsPrivateAudio(){val f=Fixture(now);val e=f.engine();val key=e.prepare(rule,Version(1,1)).active.single().key;f.unlocked=false;f.audio.forbidRead=true;f.now=Instant.parse(rule.atUtc);val d=e.onTrigger(key);assertTrue(d.play);assertTrue(d.fallbackTone);assertNull(d.audioRef)}
 @Test fun cancelRemovesPlaybackAuthorityAfterTrigger(){val f=Fixture(now);val e=f.engine();val key=e.prepare(rule,Version(1,1)).active.single().key;f.now=Instant.parse(rule.atUtc);assertTrue(e.onTrigger(key).play);assertTrue(e.canPlay(key.token));e.cancel(rule.scheduleId,rule.epochId);assertFalse(e.canPlay(key.token))}
 @Test fun oldVersionFiringDoesNotHideNewCandidateFailure(){val f=Fixture(now);val e=f.engine();val old=e.prepare(rule,Version(1,1)).active.single().key;f.audio.fail=true;e.prepare(rule.copy(body="Failed newer content"),Version(2,2));f.now=Instant.parse(rule.atUtc);assertTrue(e.onTrigger(old).play);assertEquals("blocked_audio",e.report(rule.scheduleId).state)}
 @Test fun cancelledTokenNeverInvokesPlaybackStart(){val f=Fixture(now);val e=f.engine();val key=e.prepare(rule,Version(1,1)).active.single().key;f.now=Instant.parse(rule.atUtc);e.onTrigger(key);var started=0;assertTrue(e.withPlaybackAuthority(key.token){started++});e.cancel(rule.scheduleId,rule.epochId);assertFalse(e.withPlaybackAuthority(key.token){started++});assertEquals(1,started)}
 @Test fun consumedOneShotsDoNotExhaustActiveLimit(){val f=Fixture(now);val e=f.engine();repeat(51){val r=rule.copy(scheduleId=java.util.UUID.randomUUID().toString(),epochId=java.util.UUID.randomUUID().toString(),atUtc=f.now.plusSeconds(60).toString());val key=e.prepare(r,Version(null,1)).active.single().key;f.now=Instant.parse(r.atUtc);assertTrue(e.onTrigger(key).play);e.finish(key.token,"fired")}}
 @Test fun recoveredOneShotReportsInterrupted(){val f=Fixture(now);val e=f.engine();val key=e.prepare(rule,Version(null,1)).active.single().key;f.now=Instant.parse(rule.atUtc);e.onTrigger(key);val recovered=f.engine();recovered.reconcile(RecoveryReason.APP_OPEN);assertEquals("interrupted",recovered.report(rule.scheduleId).state)}
 @Test fun priorLedgerEntriesGetSafeDefaultsForAddedMetadata(){val f=Fixture(now);val entry=f.engine().prepare(rule,Version(null,1)).active.single();val gson=com.google.gson.Gson();val json=gson.toJsonTree(entry).asJsonObject;listOf("claimOwner","kind","parentToken","respectSilent").forEach(json::remove);val restored=gson.fromJson(json,AlarmEntry::class.java);assertEquals("regular",restored.copy(phase="cancelled").kind);assertFalse(restored.respectSilent)}
 @Test fun expiredImportDoesNotQueueUnnecessarySpeech(){val f=Fixture(Instant.parse(rule.atUtc).plusSeconds(3600));f.audio.auto=false;val result=f.engine().prepare(rule,Version(1,1));assertEquals("missed",result.state);assertNull(f.audio.pending)}
 @Test fun expiredReplacementDoesNotKeepOldFutureAlarm(){val f=Fixture(now);val e=f.engine();e.prepare(rule,Version(1,1));val result=e.prepare(rule.copy(atUtc=now.minusSeconds(60).toString()),Version(2,2));assertEquals("missed",result.state);assertTrue(result.active.isEmpty());assertTrue(f.scheduler.scheduled.isEmpty())}
 @Test fun higherDeviceGenerationCanRebaseToANewBridge(){val f=Fixture(now);val e=f.engine();e.prepare(rule,Version(100,1));assertEquals("armed",e.prepare(rule,Version(1,2)).state);assertEquals("conflict",e.prepare(rule,Version(200,1)).state)}
}
