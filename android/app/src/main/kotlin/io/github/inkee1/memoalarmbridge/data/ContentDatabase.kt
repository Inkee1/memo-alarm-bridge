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
import java.util.UUID

data class StoredNote(val note:Note,val version:Version,val deleted:Boolean=false)

private fun privateContext(context:Context):Context {check(!context.isDeviceProtectedStorage&&context.getSystemService(UserManager::class.java).isUserUnlocked){"Unlock the phone before accessing private content"};return context.applicationContext}
class ContentDatabase(context:Context,name:String="content.db"):SQLiteOpenHelper(privateContext(context),name,null,3),SyncPort {
 private val gson=Gson()
 private val localEditor=LocalEditor(this)
 override fun onConfigure(db:SQLiteDatabase){
  db.execSQL("PRAGMA synchronous=FULL")
  // SQLiteOpenHelper starts the migration transaction after onConfigure.
  // A consistent, non-overwriting snapshot is mandatory before upgrading.
  if(db.version in 1 until 3){val backup=File(db.path+".before-v3-"+UUID.randomUUID()+".db");db.execSQL("VACUUM INTO ?",arrayOf(backup.path))}
 }
 override fun onCreate(db:SQLiteDatabase){
  db.execSQL("CREATE TABLE schedules(id TEXT PRIMARY KEY,rule TEXT NOT NULL,server_revision INTEGER,local_generation INTEGER NOT NULL)")
  db.execSQL("CREATE TABLE audio(ref TEXT PRIMARY KEY,sha256 TEXT NOT NULL,bytes INTEGER NOT NULL)")
  createNotes(db)
  createSync(db)
 }
 private fun createNotes(db:SQLiteDatabase){db.execSQL("CREATE TABLE notes(id TEXT PRIMARY KEY,payload TEXT NOT NULL,server_revision INTEGER,local_generation INTEGER NOT NULL,deleted INTEGER NOT NULL DEFAULT 0)")}
 private fun createSync(db:SQLiteDatabase){db.execSQL("CREATE TABLE sync_state(id INTEGER PRIMARY KEY CHECK(id=1),payload TEXT NOT NULL)");db.execSQL("INSERT INTO sync_state VALUES(1,'{}')")}
 override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){if(oldVersion in 1..2&&newVersion==3){if(oldVersion==1)createNotes(db);createSync(db)}else throw SQLiteException("Unsupported content migration; original database retained")}
 override fun onDowngrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){throw SQLiteException("Newer content requires a newer app; original retained")}
 fun saveRule(rule:ScheduleRule,version:Version){validateRule(rule).requireValid();require(version.localGeneration>0);val cv=ContentValues().apply{put("id",rule.scheduleId);put("rule",gson.toJson(rule));if(version.serverRevision==null)putNull("server_revision")else put("server_revision",version.serverRevision);put("local_generation",version.localGeneration)};check(writableDatabase.insertWithOnConflict("schedules",null,cv,SQLiteDatabase.CONFLICT_REPLACE)!=-1L)}
 fun rule(id:String):Pair<ScheduleRule,Version>?{readableDatabase.rawQuery("SELECT rule,server_revision,local_generation FROM schedules WHERE id=?",arrayOf(id)).use{c->if(!c.moveToFirst())return null;val r=gson.fromJson(c.getString(0),ScheduleRule::class.java);validateRule(r).requireValid();return r to Version(if(c.isNull(1))null else c.getLong(1),c.getLong(2))}}
 fun rules():List<Pair<ScheduleRule,Version>>{val out=mutableListOf<Pair<ScheduleRule,Version>>();readableDatabase.rawQuery("SELECT rule,server_revision,local_generation FROM schedules",null).use{c->while(c.moveToNext()){val r=gson.fromJson(c.getString(0),ScheduleRule::class.java);validateRule(r).requireValid();out+=r to Version(if(c.isNull(1))null else c.getLong(1),c.getLong(2))}};return out}
 fun saveAudio(audio:ReadyAudio){require(validId(audio.ref)&&validHash(audio.sha256)&&audio.bytes in 1..25L*1024*1024);val cv=ContentValues().apply{put("ref",audio.ref);put("sha256",audio.sha256);put("bytes",audio.bytes)};check(writableDatabase.insertWithOnConflict("audio",null,cv,SQLiteDatabase.CONFLICT_REPLACE)!=-1L)}
 fun audio(ref:String):ReadyAudio?{readableDatabase.rawQuery("SELECT sha256,bytes FROM audio WHERE ref=?",arrayOf(ref)).use{c->if(!c.moveToFirst())return null;return ReadyAudio(ref,c.getString(0),c.getLong(1))}}
 @Synchronized fun editRule(rule:ScheduleRule,expectedVersion:Version?=null):Version=localEditor.saveRule(rule,expectedVersion).version
 @Synchronized fun saveNote(note:Note,expectedVersion:Version?=null):StoredNote {
  val saved=localEditor.saveNote(note,expectedVersion)
  return StoredNote(gson.fromJson(saved.payload,Note::class.java),saved.version,saved.deleted)
 }
 @Synchronized fun deleteNote(id:String,expectedVersion:Version)=localEditor.deleteNote(id,expectedVersion)
 fun putNote(value:StoredNote){validateNote(value.note).requireValid();require(value.version.localGeneration>0);val cv=ContentValues().apply{put("id",value.note.noteId);put("payload",gson.toJson(value.note));if(value.version.serverRevision==null)putNull("server_revision")else put("server_revision",value.version.serverRevision);put("local_generation",value.version.localGeneration);put("deleted",if(value.deleted)1 else 0)};check(writableDatabase.insertWithOnConflict("notes",null,cv,SQLiteDatabase.CONFLICT_REPLACE)!=-1L)}
 fun notes(includeDeleted:Boolean=false):List<StoredNote>{val out=mutableListOf<StoredNote>();readableDatabase.rawQuery("SELECT payload,server_revision,local_generation,deleted FROM notes"+(if(includeDeleted)"" else " WHERE deleted=0")+" ORDER BY rowid DESC",null).use{c->while(c.moveToNext()){val n=gson.fromJson(c.getString(0),Note::class.java);validateNote(n).requireValid();out+=StoredNote(n,Version(if(c.isNull(1))null else c.getLong(1),c.getLong(2)),c.getInt(3)!=0)}};return out}
 @Synchronized override fun read():SyncState {
  val metadata=readableDatabase.rawQuery("SELECT payload FROM sync_state WHERE id=1",null).use{c->check(c.moveToFirst());requireNotNull(gson.fromJson(c.getString(0),SyncState::class.java))}
  val records=mutableMapOf<String,LocalRecord>()
  fun append(raw:LocalRecord){val saved=metadata.records[raw.key];records[raw.key]=if(saved==null)raw else saved.copy(payload=raw.payload,version=raw.version,deleted=raw.deleted,dirty=raw.version.localGeneration>saved.version.localGeneration||saved.dirty,syncError=if(raw.version.localGeneration>saved.version.localGeneration)""else saved.syncError)}
  rules().forEach{(rule,version)->append(LocalRecord("schedule",rule.scheduleId,gson.toJsonTree(rule).asJsonObject,version))}
  notes(true).forEach{append(LocalRecord("note",it.note.noteId,gson.toJsonTree(it.note).asJsonObject,it.version,it.deleted))}
  return metadata.copy(records=records)
 }
 @Synchronized override fun write(value:SyncState){
  require(value.cursor>=0&&value.inFlight.size<=16)
  value.records.forEach{(key,r)->require(key==r.key&&r.version.localGeneration>0&&(r.version.serverRevision?:1)>0);if(r.entityType=="note")validateNote(gson.fromJson(r.payload,Note::class.java)).requireValid()else{require(r.entityType=="schedule");validateRule(gson.fromJson(r.payload,ScheduleRule::class.java)).requireValid()}}
  value.inFlight.values.forEach{validateMutation(it.operation).requireValid();require(it.generation>0)}
  val previous=read();val db=writableDatabase;db.beginTransaction()
  try{
   value.records.values.forEach{r->val old=previous.records[r.key];if(old==null||old.payload!=r.payload||old.version!=r.version||old.deleted!=r.deleted){if(r.entityType=="note")putNote(StoredNote(gson.fromJson(r.payload,Note::class.java),r.version,r.deleted))else saveRule(gson.fromJson(r.payload,ScheduleRule::class.java),r.version)}}
   (previous.records.keys-value.records.keys).forEach{key->val old=previous.records.getValue(key);db.delete(if(old.entityType=="note")"notes"else "schedules","id=?",arrayOf(old.entityId))}
   // Payloads live once in their SQLite rows; the sync JSON holds only metadata.
   val metadata=value.copy(records=value.records.mapValues{(_,r)->r.copy(payload=com.google.gson.JsonObject())})
   val cv=ContentValues().apply{put("payload",gson.toJson(metadata))};check(db.update("sync_state",cv,"id=1",null)==1);db.setTransactionSuccessful()
  }finally{db.endTransaction()}
 }
}
