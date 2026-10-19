package io.github.inkee1.memoalarmbridge

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import io.github.inkee1.memoalarmbridge.core.*
import io.github.inkee1.memoalarmbridge.data.ContentDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ContentSyncTest {
    @Test fun restoredDraftCannotOverwriteACommittedRemoteChange(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="draft-test-"+UUID.randomUUID()+".db"
        var db=ContentDatabase(context,name)
        try {
            val saved=db.saveNote(Note(UUID.randomUUID().toString(),"Original note"))
            val draft=Bundle().apply{putString("note.base",Gson().toJson(saved.version));putString("note.text","Unsaved phone draft")}
            val before=db.read();val key="note:"+saved.note.noteId;val old=before.records.getValue(key)
            val remote=old.copy(payload=old.payload.deepCopy().apply{addProperty("text","Received from PC")},version=Version(2,2),dirty=false)
            db.write(before.copy(cursor=2,records=before.records+(key to remote)))
            db.close();db=ContentDatabase(context,name)
            val base=Gson().fromJson(draft.getString("note.base"),Version::class.java)
            try {db.saveNote(saved.note.copy(text=draft.getString("note.text")!!),base);fail("stale draft accepted")}
            catch(e:StaleDraftException){assertEquals("Received from PC",e.current!!.payload["text"].asString)}
            assertEquals("Received from PC",db.notes().single().note.text)
            assertEquals("Unsaved phone draft",draft.getString("note.text"));assertEquals(2L,db.read().cursor)
        } finally {db.close();context.deleteDatabase(name)}
    }
    @Test fun contentAndCursorCommitTogether(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="sync-test-"+UUID.randomUUID()+".db"
        var db=ContentDatabase(context,name)
        try {
            val note=db.saveNote(Note(UUID.randomUUID().toString(),"Synthetic transaction test"))
            val before=db.read();val key="note:"+note.note.noteId
            db.writableDatabase.execSQL("CREATE TRIGGER fail_sync BEFORE UPDATE ON sync_state BEGIN SELECT RAISE(ABORT,'injected failure'); END;")
            try {db.write(before.copy(cursor=9,records=before.records+(key to before.records.getValue(key).copy(payload=before.records.getValue(key).payload.deepCopy().apply{addProperty("text","must roll back")}))));fail("commit accepted") }catch(_:android.database.SQLException){}
            assertEquals(0L,db.read().cursor);assertEquals("Synthetic transaction test",db.notes().single().note.text)
            db.writableDatabase.execSQL("DROP TRIGGER fail_sync");db.write(before.copy(cursor=9));db.close();db=ContentDatabase(context,name)
            assertEquals(9L,db.read().cursor);assertEquals(1L,db.notes().single().version.localGeneration)
        } finally {db.close();context.deleteDatabase(name)}
    }
}
