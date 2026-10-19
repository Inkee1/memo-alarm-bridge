package io.github.inkee1.memoalarmbridge.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class AcknowledgementTest {
    private val id="11111111-1111-4111-8111-111111111111"
    private val epoch="22222222-2222-4222-8222-222222222222"
    private val device="33333333-3333-4333-8333-333333333333"
    private val ref="44444444-4444-4444-8444-444444444444"
    private val now=Instant.parse("2030-01-01T00:00:00Z")
    private val rule=ScheduleRule(id,epoch,atUtc="2030-01-01T01:00:00Z",body="test")
    private fun entry(version:Version)=AlarmEntry(OccurrenceKey("55555555-5555-4555-8555-555555555555",id,epoch,version),rule.atUtc,"active",ref)
    @Test fun oldAppliedVersionCannotAcknowledgeNewDesiredVersion(){val ack=buildDeviceAck(device,rule,Version(2,2),PrepareResult("blocked_audio",listOf(entry(Version(1,1)))),false,mapOf("exact_alarm" to "granted"),{"a".repeat(64)},now)!!;assertEquals("blocked_audio",ack.state);assertTrue(ack.armedOccurrences.isEmpty());assertEquals(1L,ack.previousApplied.single().serverRevision)}
    @Test fun armedRequiresReadyAudioAndFutureOsEvidence(){val ack=buildDeviceAck(device,rule,Version(1,1),PrepareResult("armed",listOf(entry(Version(1,1)))),false,mapOf("exact_alarm" to "granted"),{null},now)!!;assertEquals("blocked_audio",ack.state);assertTrue(ack.armedOccurrences.isEmpty())}
    @Test fun durableCancellationCanAcknowledgeNeverPreparedRule(){val ack=buildDeviceAck(device,rule.copy(enabled=false),Version(2,2),PrepareResult("local_only"),true,emptyMap(),{null},now)!!;assertEquals("cancelled",ack.state)}
    @Test fun standaloneRuleNeverInventsServerRevision(){assertNull(buildDeviceAck(device,rule,Version(null,1),PrepareResult("armed",listOf(entry(Version(null,1)))),false,emptyMap(),{null},now))}
    @Test fun formerBridgeRevisionIsNotAttributedToNewBridge(){val ack=buildDeviceAck(device,rule,Version(1,3),PrepareResult("preparing",listOf(entry(Version(100,1)))),false,mapOf("exact_alarm" to "granted"),{"a".repeat(64)},now,serverGenerationFloor=2)!!;assertEquals(0L,ack.previousApplied.single().serverRevision)}
}
