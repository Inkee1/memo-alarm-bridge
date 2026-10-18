package io.github.inkee1.memoalarmbridge.core
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatterBuilder
import java.util.UUID

private val instantFormat=DateTimeFormatterBuilder().appendInstant(-1).toFormatter()
fun occurrenceId(rule:ScheduleRule,at:String):String {
 val bytes=MessageDigest.getInstance("SHA-256").digest((rule.scheduleId+"|"+rule.epochId+"|"+at).toByteArray(Charsets.UTF_8))
 bytes[6]=((bytes[6].toInt() and 15) or 128).toByte();bytes[8]=((bytes[8].toInt() and 63) or 128).toByte()
 val buffer=ByteBuffer.wrap(bytes);return UUID(buffer.long,buffer.long).toString()
}
fun nextOccurrences(rule:ScheduleRule,after:Instant,count:Int):List<Occurrence>{
 validateRule(rule).requireValid();require(count in 1..100)
 if(!rule.enabled)return emptyList()
 fun item(at:Instant):Occurrence{val text=instantFormat.format(at);return Occurrence(occurrenceId(rule,text),text)}
 if(rule.kind=="one_shot"){val at=Instant.parse(rule.atUtc);return if(at>after)listOf(item(at)) else emptyList()}
 val zone=ZoneId.of(rule.zoneId);val clock=LocalTime.parse(rule.localTime);val first=after.atZone(zone).toLocalDate()
 val out=mutableListOf<Occurrence>()
 for(i in 0L until 3700L){
  val date=first.plusDays(i);if(rule.kind=="weekly"&&date.dayOfWeek.value !in rule.weekDays)continue
  val at=date.atTime(clock).atZone(zone).withEarlierOffsetAtOverlap().toInstant()
  if(at>after)out+=item(at)
  if(out.size==count)break
 };return out
}
