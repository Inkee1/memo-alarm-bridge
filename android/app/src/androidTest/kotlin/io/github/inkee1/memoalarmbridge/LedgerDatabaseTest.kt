package io.github.inkee1.memoalarmbridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.inkee1.memoalarmbridge.core.ExecutionLedger
import io.github.inkee1.memoalarmbridge.data.LedgerDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LedgerDatabaseTest {
 @Test fun cancellationSurvivesReopenAndRollback(){
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val name="ledger-test-"+UUID.randomUUID()+".db"
  val marker="11111111-1111-4111-8111-111111111111:22222222-2222-4222-8222-222222222222"
  try{var db=LedgerDatabase(context,name);db.write(ExecutionLedger(cancelledEpochs=setOf(marker)));db.close();db=LedgerDatabase(context,name)
   try{assertTrue(db.read().cancelledEpochs.contains(marker));db.writableDatabase.execSQL("CREATE TRIGGER test_interrupt BEFORE UPDATE ON ledger BEGIN SELECT RAISE(ABORT,'injected failure'); END;");try{db.write(ExecutionLedger());fail("injected write succeeded")}catch(_:android.database.SQLException){};assertTrue(db.read().cancelledEpochs.contains(marker))}finally{db.close()}
  }finally{context.createDeviceProtectedStorageContext().deleteDatabase(name)}
 }
}
