package io.github.inkee1.memoalarmbridge.data

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.os.UserManager
import com.google.gson.Gson
import io.github.inkee1.memoalarmbridge.core.*
import java.io.File
import java.time.Instant
import java.util.UUID

data class StoredNote(val note:Note,val version:Version,val deleted:Boolean=false)

private fun privateContext(context:Context):Context {check(!context.isDeviceProtectedStorage&&context.getSystemService(UserManager::class.java).isUserUnlocked){"Unlock the phone before accessing private content"};return context.applicationContext}
class ContentDatabase(context:Context):SQLiteOpenHelper(privateContext(context),"content.db",null,2) {
 private val gson=Gson()
 override fun onConfigure(db:SQLiteDatabase){
  db.execSQL("PRAGMA synchronous=FULL")
  // SQLiteOpenHelper starts the migration transaction after onConfigure.
  // A consistent, non-overwriting snapshot is mandatory before upgrading.
  if(db.version in 1 until 2){val backup=File(db.path+".before-v2-"+UUID.randomUUID()+".db");db.execSQL("VACUUM INTO ?",arrayOf(backup.path))}
 }
 override fun onCreate(db:SQLiteDatabase){
  db.execSQL("CREATE TABLE schedules(id TEXT PRIMARY KEY,rule TEXT NOT NULL,server_revision INTEGER,local_generation INTEGER NOT NULL)")
  db.execSQL("CREATE TABLE audio(ref TEXT PRIMARY KEY,sha256 TEXT NOT NULL,bytes INTEGER NOT NULL)")
  createNotes(db)
 }
 private fun createNotes(db:SQLiteDatabase){db.execSQL("CREATE TABLE notes(id TEXT PRIMARY KEY,payload TEXT NOT NULL,server_revision INTEGER,local_generation INTEGER NOT NULL,deleted INTEGER NOT NULL DEFAULT 0)")}
 override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){if(oldVersion==1&&newVersion==2)createNotes(db)else throw SQLiteException("Unsupported content migration; original database retained")}
 override fun onDowngrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){throw SQLiteException("Newer content requires a newer app; original retained")}
 fun saveRule(rule:ScheduleRule,version:Version){validateRule(rule).requireValid();require(version.localGeneration>0);val cv=ContentValues().apply{put("id",rule.scheduleId);put("rule",gson.toJson(rule));if(version.serverRevision==null)putNull("server_revision")else put("server_revision",version.serverRevision);put("local_generation",version.localGeneration)};check(writableDatabase.insertWithOnConflict("schedules",null,cv,SQLiteDatabase.CONFLICT_REPLACE)!=-1L)}
 fun rule(id:String):Pair<ScheduleRule,Version>?{readableDatabase.rawQuery("SELECT rule,server_revision,local_generation FROM schedules WHERE id=?",arrayOf(id)).use{c->if(!c.moveToFirst())return null;val r=gson.fromJson(c.getString(0),ScheduleRule::class.java);validateRule(r).requireValid();return r to Version(if(c.isNull(1))null else c.getLong(1),c.getLong(2))}}
 fun rules():List<Pair<ScheduleRule,Version>>{val out=mutableListOf<Pair<ScheduleRule,Version>>();readableDatabase.rawQuery("SELECT rule,server_revision,local_generation FROM schedules",null).use{c->while(c.moveToNext()){val r=gson.fromJson(c.getString(0),ScheduleRule::class.java);validateRule(r).requireValid();out+=r to Version(if(c.isNull(1))null else c.getLong(1),c.getLong(2))}};return out}
 fun saveAudio(audio:ReadyAudio){require(validId(audio.ref)&&validHash(audio.sha256)&&audio.bytes in 1..25L*1024*1024);val cv=ContentValues().apply{put("ref",audio.ref);put("sha256",audio.sha256);put("bytes",audio.bytes)};check(writableDatabase.insertWithOnConflict("audio",null,cv,SQLiteDatabase.CONFLICT_REPLACE)!=-1L)}
 fun audio(ref:String):ReadyAudio?{readableDatabase.rawQuery("SELECT sha256,bytes FROM audio WHERE ref=?",arrayOf(ref)).use{c->if(!c.moveToFirst())return null;return ReadyAudio(ref,c.getString(0),c.getLong(1))}}
 @Synchronized fun editRule(rule:ScheduleRule):Version {
  val previous=rule(rule.scheduleId);val version=Version(previous?.second?.serverRevision,(previous?.second?.localGeneration?:0)+1)
  saveRule(rule,version);return version
 }
 @Synchronized fun saveNote(note:Note):StoredNote {
  validateNote(note).requireValid();val previous=notes(true).find{it.note.noteId==note.noteId};val now=Instant.now().toString()
  val value=StoredNote(note.copy(createdAt=previous?.note?.createdAt?.takeIf{it.isNotEmpty()}?:now,updatedAt=now),Version(previous?.version?.serverRevision,(previous?.version?.localGeneration?:0)+1))
  putNote(value);return value
 }
 @Synchronized fun deleteNote(id:String){require(validId(id));val previous=notes(true).find{it.note.noteId==id}?:return;putNote(previous.copy(note=previous.note.copy(text="",audioBlobSha256="",updatedAt=Instant.now().toString()),version=previous.version.copy(localGeneration=previous.version.localGeneration+1),deleted=true))}
 fun putNote(value:StoredNote){validateNote(value.note).requireValid();require(value.version.localGeneration>0);val cv=ContentValues().apply{put("id",value.note.noteId);put("payload",gson.toJson(value.note));if(value.version.serverRevision==null)putNull("server_revision")else put("server_revision",value.version.serverRevision);put("local_generation",value.version.localGeneration);put("deleted",if(value.deleted)1 else 0)};check(writableDatabase.insertWithOnConflict("notes",null,cv,SQLiteDatabase.CONFLICT_REPLACE)!=-1L)}
 fun notes(includeDeleted:Boolean=false):List<StoredNote>{val out=mutableListOf<StoredNote>();readableDatabase.rawQuery("SELECT payload,server_revision,local_generation,deleted FROM notes"+(if(includeDeleted)"" else " WHERE deleted=0")+" ORDER BY rowid DESC",null).use{c->while(c.moveToNext()){val n=gson.fromJson(c.getString(0),Note::class.java);validateNote(n).requireValid();out+=StoredNote(n,Version(if(c.isNull(1))null else c.getLong(1),c.getLong(2)),c.getInt(3)!=0)}};return out}
}
