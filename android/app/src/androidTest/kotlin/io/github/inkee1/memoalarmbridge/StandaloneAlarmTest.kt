package io.github.inkee1.memoalarmbridge

import android.content.Intent
import android.widget.EditText
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.inkee1.memoalarmbridge.ui.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StandaloneAlarmTest {
 @Test fun appSavesANoteWithoutABridge(){
  val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
  val marker="Synthetic standalone test "+UUID.randomUUID()
  val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
  val graph=AppGraph.get(context)
  try{
   instrumentation.runOnMainSync {activity.findViewById<EditText>(R.id.note_text).setText(marker);activity.findViewById<Button>(R.id.save_note).performClick()}
   val until=System.nanoTime()+5_000_000_000L
   while(System.nanoTime()<until&&graph.content().notes().none{it.note.text==marker})Thread.sleep(50)
   val found=graph.content().notes().single{it.note.text==marker}
   assertNull(found.version.serverRevision);assertEquals(1L,found.version.localGeneration)
  }finally{
   graph.content().notes().filter{it.note.text==marker}.forEach{graph.content().deleteNote(it.note.noteId)}
   instrumentation.runOnMainSync{activity.finish()}
  }
 }
}
