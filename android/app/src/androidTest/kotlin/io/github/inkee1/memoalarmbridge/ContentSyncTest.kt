package io.github.inkee1.memoalarmbridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.inkee1.memoalarmbridge.core.*
import io.github.inkee1.memoalarmbridge.data.ContentDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ContentSyncTest {
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
