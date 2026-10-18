package io.github.inkee1.memoalarmbridge.core

import org.junit.Assert.*
import org.junit.Test

class ModelTest {
 @Test fun mutationRejectsUnknownPayloadFields() {
  val id="11111111-1111-4111-8111-111111111111"
  val payload=com.google.gson.JsonParser.parseString("{\"note_id\":\""+id+"\",\"text\":\"test\"}").asJsonObject
  val m=Mutation(operationId="33333333-3333-4333-8333-333333333333",entityType="note",entityId=id,baseRevision=0,action="upsert",payload=payload)
  assertTrue(validateMutation(m).valid)
  payload.addProperty("shell","ignored")
  assertFalse(validateMutation(m).valid)
 }
 @Test fun unicodeLimitsAndUnknownMutationFields() {
  val id="11111111-1111-4111-8111-111111111111"
  val note=Note(noteId=id,text="🙂".repeat(32768))
  assertTrue(validateNote(note).valid)
  assertFalse(validateNote(note.copy(text=note.text+"🙂")).valid)
  val rule=ScheduleRule(scheduleId=id,epochId="22222222-2222-4222-8222-222222222222",kind="daily",localTime="09:00",zoneId="UTC",body="가".repeat(2000))
  assertTrue(validateRule(rule).valid)
  assertFalse(validateRule(rule.copy(body=rule.body+"가")).valid)
 }
}
