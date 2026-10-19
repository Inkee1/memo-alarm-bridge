package io.github.inkee1.memoalarmbridge.core

import java.time.Instant
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class RecoveryTest {
 private class Ledger:LedgerPort {var value=ExecutionLedger();override fun read()=value;override fun write(value:ExecutionLedger){this.value=value}}
 private class Scheduler:SchedulerPort {val scheduled=mutableMapOf<String,Instant>();override fun canScheduleExact()=true;override fun register(key:OccurrenceKey,at:Instant){scheduled[key.token]=at};override fun cancel(key:OccurrenceKey){scheduled.remove(key.token)}}
 private class Audio:AudioPort {val files=mutableMapOf<String,ReadyAudio>();var forbid=false;override fun request(rule:ScheduleRule,ref:String,done:(Result<ReadyAudio>)->Unit){check(!forbid);val a=ReadyAudio(ref,"a".repeat(64),1000);files[ref]=a;done(Result.success(a))};override fun inspect(ref:String):ReadyAudio?{check(!forbid);return files[ref]}}
 private class Rig {
  val ledger=Ledger();val scheduler=Scheduler();val audio=Audio();var now=Instant.parse("2029-12-31T23:00:00Z");var unlocked=true;var reads=0
  val rule=ScheduleRule(scheduleId="11111111-1111-4111-8111-111111111111",epochId="22222222-2222-4222-8222-222222222222",kind="daily",localTime="09:00",zoneId="Asia/Seoul",body="Test daily reminder")
  fun engine()=AlarmEngine(ledger,scheduler,audio,{now},{unlocked},{reads++;listOf(rule to Version(1,1))})
 }
 @Test fun nextOccurrenceWithoutPc(){val f=Rig();val e=f.engine();val first=e.prepare(f.rule,Version(1,1)).active.minBy{it.atUtc};f.now=Instant.parse(first.atUtc);assertTrue(e.onTrigger(first.key).play);e.finish(first.key.token,"fired");f.now=f.now.plusSeconds(1);e.reconcile(RecoveryReason.TRIGGER);assertEquals(setOf(Instant.parse("2030-01-02T00:00:00Z"),Instant.parse("2030-01-03T00:00:00Z")),f.scheduler.scheduled.values.toSet())}
 @Test fun rebootSkipsPast(){val f=Rig();f.engine().prepare(f.rule,Version(1,1));f.scheduler.scheduled.clear();f.now=Instant.parse("2030-01-04T01:00:00Z");f.engine().reconcile(RecoveryReason.BOOT);assertTrue(f.ledger.value.entries.values.any{it.phase=="missed"});assertEquals(setOf(Instant.parse("2030-01-05T00:00:00Z"),Instant.parse("2030-01-06T00:00:00Z")),f.scheduler.scheduled.values.toSet())}
 @Test fun lockedBootUsesGenericTone(){val f=Rig();val e=f.engine();e.prepare(f.rule,Version(1,1));f.unlocked=false;f.audio.forbid=true;f.scheduler.scheduled.clear();e.reconcile(RecoveryReason.LOCKED_BOOT);assertEquals(0,f.reads);assertEquals(2,f.scheduler.scheduled.size);val first=e.report(f.rule.scheduleId).active.minBy{it.atUtc};f.now=Instant.parse(first.atUtc);assertTrue(e.onTrigger(first.key).fallbackTone)}
 @Test fun timeZoneChangeKeepsPinnedZone(){val f=Rig();val e=f.engine();e.prepare(f.rule,Version(1,1));val old=TimeZone.getDefault();try{TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));e.reconcile(RecoveryReason.TIME_CHANGED);assertTrue(f.scheduler.scheduled.values.contains(Instant.parse("2030-01-01T00:00:00Z")))}finally{TimeZone.setDefault(old)}}
 @Test fun interruptedPlaybackDoesNotAutoReplay(){val f=Rig();val old=f.engine();val first=old.prepare(f.rule,Version(1,1)).active.minBy{it.atUtc};f.now=Instant.parse(first.atUtc);assertTrue(old.onTrigger(first.key).play);f.scheduler.scheduled.clear();val recovered=f.engine();assertFalse(recovered.canPlay(first.key.token));f.now=f.now.plusSeconds(10);recovered.reconcile(RecoveryReason.APP_OPEN);assertEquals("interrupted",f.ledger.value.entries[first.key.token]?.phase);assertFalse(recovered.onTrigger(first.key).play);assertTrue(f.scheduler.scheduled.values.all{it>f.now})}
 @Test fun snoozeDoesNotMoveRecurringRule(){val f=Rig();val e=f.engine();val first=e.prepare(f.rule,Version(1,1)).active.minBy{it.atUtc};f.now=Instant.parse(first.atUtc);e.onTrigger(first.key);e.snooze(first.key.token);assertFalse(e.canPlay(first.key.token));assertTrue(f.scheduler.scheduled.values.contains(Instant.parse("2030-01-01T00:05:00Z")));assertTrue(f.scheduler.scheduled.values.contains(Instant.parse("2030-01-02T00:00:00Z")));assertEquals("09:00",f.rule.localTime)}
 @Test fun replenishingFutureDoesNotStopCurrentSpeechOrSnooze(){val f=Rig();val e=f.engine();val first=e.prepare(f.rule,Version(1,1)).active.minBy{it.atUtc};f.now=Instant.parse(first.atUtc);e.onTrigger(first.key);f.now=f.now.plusSeconds(1);e.reconcile(RecoveryReason.TRIGGER);assertTrue(e.canPlay(first.key.token));e.snooze(first.key.token);e.reconcile(RecoveryReason.TRIGGER);assertTrue(f.scheduler.scheduled.values.contains(Instant.parse("2030-01-01T00:05:01Z")))}
}
