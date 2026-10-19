package io.github.inkee1.memoalarmbridge.core

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class LocalEditorTest {
    private val gson=Gson()
    private val id="11111111-1111-4111-8111-111111111111"
    private val epoch="22222222-2222-4222-8222-222222222222"
    private class Repo:SyncPort {var value=SyncState(linked=true);override fun read()=value;override fun write(value:SyncState){this.value=value}}
    private fun record(type:String,payload:Any,version:Version=Version(1,1),deleted:Boolean=false)=LocalRecord(type,id,gson.toJsonTree(payload).asJsonObject,version,deleted,dirty=false)
    private fun install(repo:Repo,value:LocalRecord){repo.value=repo.value.copy(records=repo.value.records+(value.key to value))}
    private fun editor(repo:Repo)=LocalEditor(repo){Instant.parse("2030-01-01T00:00:00Z")}
    private fun stale(job:()->Unit):StaleDraftException {try{job();fail("stale draft replaced the current stored record")}catch(e:StaleDraftException){return e};throw AssertionError()}
    @Test fun openNoteCannotOverwritePCUpdateReceivedBySync(){
        val repo=Repo();val old=record("note",Note(id,"original"));install(repo,old);repo.value=repo.value.copy(cursor=1)
        val draft=Note(id,"phone draft")
        val change=Mutation(operationId="33333333-3333-4333-8333-333333333333",entityType="note",entityId=id,baseRevision=1,action="upsert",payload=gson.toJsonTree(Note(id,"PC change")).asJsonObject)
        SyncEngine(repo,object:SyncTransport{override fun exchange(request:SyncRequest)=SyncResponse(cursor=2,changes=listOf(RemoteChange(2,change)))}).syncOnce()
        val current=repo.value.records.getValue(old.key);val error=stale{editor(repo).saveNote(draft,old.version)}
        assertEquals("PC change",error.current!!.payload["text"].asString);assertEquals(current,repo.value.records.getValue(old.key));assertEquals("phone draft",draft.text)
    }
    @Test fun openScheduleCannotReplaceNewTimeOrCancellation(){
        val repo=Repo();val rule=ScheduleRule(id,epoch,kind="daily",localTime="09:00",zoneId="Asia/Seoul",body="same speech")
        val old=record("schedule",rule);install(repo,old);val current=record("schedule",rule.copy(localTime="10:30",enabled=false),Version(2,2));install(repo,current)
        stale{editor(repo).saveRule(rule.copy(body="phone draft"),old.version)};assertEquals(current,repo.value.records.getValue(old.key))
    }
    @Test fun remotelyDeletedNoteRequiresReviewAndCanBeSavedAsANewCopy(){
        val repo=Repo();val old=record("note",Note(id,"original"));val deleted=old.copy(version=Version(2,2),deleted=true);install(repo,deleted)
        assertTrue(stale{editor(repo).saveNote(Note(id,"retained draft"),old.version)}.current!!.deleted)
        val copy=editor(repo).saveNote(Note("11111111-1111-4111-8111-111111111112","retained draft"),null)
        assertEquals("retained draft",copy.payload["text"].asString);assertEquals(deleted,repo.value.records.getValue(old.key))
    }
    @Test fun restoredDraftKeepsItsOldBaseInsteadOfReadingTheLatestVersion(){
        val repo=Repo();val base=Version(5,8);val restored=gson.fromJson(gson.toJson(base),Version::class.java)
        val current=record("note",Note(id,"received while closed"),Version(6,9));install(repo,current)
        stale{editor(repo).saveNote(Note(id,"restored unsaved draft"),restored)};assertEquals(current,repo.value.records.getValue(current.key))
    }
    @Test fun localGenerationChangeAndUnexpectedExistingIdAlsoReject(){
        val repo=Repo();val current=record("note",Note(id,"other local edit"),Version(1,3));install(repo,current)
        stale{editor(repo).saveNote(Note(id,"stale"),Version(1,2))};stale{editor(repo).saveNote(Note(id,"new ID collision"),null)}
        assertEquals(current,repo.value.records.getValue(current.key))
    }
    @Test fun matchingBaseSavesDirtyRecordAndPreservesOtherSyncState(){
        val repo=Repo();val old=record("note",Note(id,"old",createdAt="2029-01-01T00:00:00Z"));install(repo,old);repo.value=repo.value.copy(cursor=7,lastContact="2030-01-01T00:00:00Z")
        val saved=editor(repo).saveNote(Note(id,"edited"),old.version)
        assertEquals(Version(1,2),saved.version);assertTrue(saved.dirty);assertEquals("edited",saved.payload["text"].asString);assertEquals("2029-01-01T00:00:00Z",saved.payload["created_at"].asString);assertEquals(7L,repo.value.cursor)
    }
    @Test fun staleDeleteCannotRemoveAChangedNote(){
        val repo=Repo();val current=record("note",Note(id,"new content"),Version(2,2));install(repo,current)
        stale{editor(repo).deleteNote(id,Version(1,1))};assertFalse(repo.value.records.getValue(current.key).deleted)
        editor(repo).deleteNote(id,current.version);assertTrue(repo.value.records.getValue(current.key).deleted);assertTrue(repo.value.records.getValue(current.key).dirty)
    }
}
