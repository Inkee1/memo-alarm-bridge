package io.github.inkee1.memoalarmbridge.data

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.os.UserManager
import com.google.gson.Gson
import io.github.inkee1.memoalarmbridge.core.*

private fun privateContext(context:Context):Context {check(!context.isDeviceProtectedStorage&&context.getSystemService(UserManager::class.java).isUserUnlocked){"Unlock the phone before accessing private content"};return context.applicationContext}
class ContentDatabase(context:Context):SQLiteOpenHelper(privateContext(context),"content.db",null,1) {
 private val gson=Gson()
 override fun onConfigure(db:SQLiteDatabase){db.execSQL("PRAGMA synchronous=FULL")}
 override fun onCreate(db:SQLiteDatabase){
  db.execSQL("CREATE TABLE schedules(id TEXT PRIMARY KEY,rule TEXT NOT NULL,server_revision INTEGER,local_generation INTEGER NOT NULL)")
  db.execSQL("CREATE TABLE audio(ref TEXT PRIMARY KEY,sha256 TEXT NOT NULL,bytes INTEGER NOT NULL)")
 }
 override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){throw SQLiteException("Unsupported content migration; original database retained")}
 override fun onDowngrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){throw SQLiteException("Newer content requires a newer app; original retained")}
 fun saveRule(rule:ScheduleRule,version:Version){validateRule(rule).requireValid();require(version.localGeneration>0);val cv=ContentValues().apply{put("id",rule.scheduleId);put("rule",gson.toJson(rule));if(version.serverRevision==null)putNull("server_revision")else put("server_revision",version.serverRevision);put("local_generation",version.localGeneration)};check(writableDatabase.insertWithOnConflict("schedules",null,cv,SQLiteDatabase.CONFLICT_REPLACE)!=-1L)}
 fun rule(id:String):Pair<ScheduleRule,Version>?{readableDatabase.rawQuery("SELECT rule,server_revision,local_generation FROM schedules WHERE id=?",arrayOf(id)).use{c->if(!c.moveToFirst())return null;val r=gson.fromJson(c.getString(0),ScheduleRule::class.java);validateRule(r).requireValid();return r to Version(if(c.isNull(1))null else c.getLong(1),c.getLong(2))}}
 fun rules():List<Pair<ScheduleRule,Version>>{val out=mutableListOf<Pair<ScheduleRule,Version>>();readableDatabase.rawQuery("SELECT rule,server_revision,local_generation FROM schedules",null).use{c->while(c.moveToNext()){val r=gson.fromJson(c.getString(0),ScheduleRule::class.java);validateRule(r).requireValid();out+=r to Version(if(c.isNull(1))null else c.getLong(1),c.getLong(2))}};return out}
 fun saveAudio(audio:ReadyAudio){require(validId(audio.ref)&&validHash(audio.sha256)&&audio.bytes in 1..25L*1024*1024);val cv=ContentValues().apply{put("ref",audio.ref);put("sha256",audio.sha256);put("bytes",audio.bytes)};check(writableDatabase.insertWithOnConflict("audio",null,cv,SQLiteDatabase.CONFLICT_REPLACE)!=-1L)}
 fun audio(ref:String):ReadyAudio?{readableDatabase.rawQuery("SELECT sha256,bytes FROM audio WHERE ref=?",arrayOf(ref)).use{c->if(!c.moveToFirst())return null;return ReadyAudio(ref,c.getString(0),c.getLong(1))}}
}
