package io.github.inkee1.memoalarmbridge.core

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class ConflictDescriptionTest {
    private val gson=Gson()
    private val id="11111111-1111-4111-8111-111111111111"
    private val epoch="22222222-2222-4222-8222-222222222222"
    private fun describe(type:String,value:Any,deleted:Boolean=false,korean:Boolean=false)=describeConflictVersion(type,gson.toJsonTree(value).asJsonObject,deleted,korean)
    @Test fun identicalSpeechStillShowsDifferentTimeZoneWeekdaysAndPolicy(){
        val first=ScheduleRule(id,epoch,kind="weekly",localTime="09:00",zoneId="Asia/Seoul",weekDays=listOf(1,3),body="same speech")
        val second=first.copy(localTime="10:30",zoneId="Europe/London",weekDays=listOf(2,4),respectSilent=true)
        val a=describe("schedule",first);val b=describe("schedule",second)
        assertTrue(a.contains("09:00"));assertTrue(b.contains("10:30"));assertTrue(a.contains("Asia/Seoul"));assertTrue(b.contains("Europe/London"));assertTrue(a.contains("Mon, Wed"));assertTrue(b.contains("Tue, Thu"));assertTrue(b.contains("Respect silent mode: Yes"));assertNotEquals(a,b)
    }
    @Test fun voiceAttachmentIdentityIsVisibleEvenWhenTextIsIdentical(){
        val a=describe("note",Note(id,"same text","a".repeat(64)));val b=describe("note",Note(id,"same text","b".repeat(64)))
        assertTrue(a.contains("a".repeat(64)));assertTrue(b.contains("b".repeat(64)));assertNotEquals(a,b)
    }
    @Test fun deletionCancellationAndOneShotTimeAreExplicitInBothLanguages(){
        assertTrue(describe("note",Note(id,"retained content"),deleted=true).contains("Deleted"))
        assertTrue(describe("note",Note(id,"retained content"),deleted=true,korean=true).contains("삭제됨"))
        val disabled=ScheduleRule(id,epoch,atUtc="2030-01-01T09:00:00Z",body="same speech",enabled=false)
        assertTrue(describe("schedule",disabled).contains("Cancelled / disabled"));assertTrue(describe("schedule",disabled).contains(disabled.atUtc));assertTrue(describe("schedule",disabled,korean=true).contains("취소 / 비활성"))
    }
}
