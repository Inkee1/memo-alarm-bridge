package io.github.inkee1.memoalarmbridge.core

import com.google.gson.Gson
import com.google.gson.JsonArray
import java.io.File
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class RecurrenceTest {
 @Test fun goldenRecurrenceFixtures() {
  val array=Gson().fromJson(File(System.getProperty("fixtures.dir"),"recurrence.json").readText(),JsonArray::class.java)
  for (element in array) {
   val o=element.asJsonObject
   val rule=Gson().fromJson(o,ScheduleRule::class.java).copy(scheduleId="11111111-1111-4111-8111-111111111111",epochId="22222222-2222-4222-8222-222222222222",body="test",enabled=true)
   val got=nextOccurrences(rule,Instant.parse(o["after"].asString),2)
   assertEquals(o["name"].asString,o["want"].asJsonArray.map{it.asString},got.map{it.atUtc})
   assertEquals(got,nextOccurrences(rule,Instant.parse(o["after"].asString),2))
  }
 }
 @Test fun invalidRulesRejected() {
  for (r in listOf(ScheduleRule(kind="monthly"),ScheduleRule(kind="daily",localTime="25:90",zoneId="UTC"),ScheduleRule(kind="weekly",localTime="09:00",zoneId="UTC"))) {
   assertThrows(IllegalArgumentException::class.java) {nextOccurrences(r,Instant.now(),2)}
  }
 }
}
