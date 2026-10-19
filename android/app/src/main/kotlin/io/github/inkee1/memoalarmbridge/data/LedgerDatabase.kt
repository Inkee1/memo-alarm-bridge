package io.github.inkee1.memoalarmbridge.data

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import com.google.gson.Gson
import io.github.inkee1.memoalarmbridge.core.*
import java.time.Instant

class LedgerDatabase(context:Context,name:String="execution.db"):SQLiteOpenHelper(context.createDeviceProtectedStorageContext(),name,null,1),LedgerPort {
 private val gson=Gson()
 override fun onConfigure(db:SQLiteDatabase){db.execSQL("PRAGMA synchronous=FULL")}
 override fun onCreate(db:SQLiteDatabase){db.execSQL("CREATE TABLE ledger(id INTEGER PRIMARY KEY CHECK(id=1),payload TEXT NOT NULL)");db.execSQL("INSERT INTO ledger VALUES(1,'{}')")}
 override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){throw SQLiteException("Unsupported ledger migration; original database retained")}
 override fun onDowngrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){throw SQLiteException("Newer ledger requires a newer app; original retained")}
 @Synchronized override fun read():ExecutionLedger {
  readableDatabase.rawQuery("SELECT payload FROM ledger WHERE id=1",null).use {c->check(c.moveToFirst()){ "Execution ledger is missing" };val s=requireNotNull(gson.fromJson(c.getString(0),ExecutionLedger::class.java));validate(s);return s}
 }
 @Synchronized override fun write(value:ExecutionLedger){
  validate(value);val db=writableDatabase;db.beginTransaction();try{val cv=ContentValues().apply{put("payload",gson.toJson(value))};check(db.update("ledger",cv,"id=1",null)==1);db.setTransactionSuccessful()}finally{db.endTransaction()}
 }
 private fun validate(s:ExecutionLedger){
  require(s.rules.size<=10000&&s.entries.size<=100000)
  s.rules.forEach{(id,r)->require(validId(id)&&id==r.scheduleId&&validId(r.epochId)&&r.desiredVersion.localGeneration>0)}
  s.entries.forEach{(token,e)->require(token==e.key.token&&validId(e.key.scheduleId)&&validId(e.key.epochId)&&validId(e.key.occurrenceId)&&validId(e.audioRef)&&e.key.version.localGeneration>0);Instant.parse(e.atUtc)}
  s.cancelledEpochs.forEach{val parts=it.split(':');require(parts.size==2&&parts.all(::validId))}
 }
}
