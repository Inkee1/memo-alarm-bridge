package io.github.inkee1.memoalarmbridge.audio

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.system.Os
import android.system.OsConstants
import io.github.inkee1.memoalarmbridge.core.*
import io.github.inkee1.memoalarmbridge.data.ContentDatabase
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TtsPreparer(private val context:Context,private val content:ContentDatabase):AudioPort {
 val files=AudioFiles(File(context.filesDir,"prepared-audio"))
 private val initialized=CompletableFuture<TextToSpeech>()
 private val pending=ConcurrentHashMap<String,CompletableFuture<Unit>>()
 private val executor=Executors.newSingleThreadExecutor()
 private var tts:TextToSpeech?=null
 init {
  Handler(Looper.getMainLooper()).post {
   tts=TextToSpeech(context){status->val engine=tts;if(status!=TextToSpeech.SUCCESS||engine==null){initialized.completeExceptionally(IllegalStateException("TTS engine unavailable"))}else{
    engine.setOnUtteranceProgressListener(object:UtteranceProgressListener(){override fun onStart(id:String){};override fun onDone(id:String){pending[id]?.complete(Unit)};@Deprecated("platform callback") override fun onError(id:String){pending[id]?.completeExceptionally(IllegalStateException("TTS failed"))};override fun onError(id:String,code:Int){pending[id]?.completeExceptionally(IllegalStateException("TTS error "+code))};override fun onStop(id:String,interrupted:Boolean){pending[id]?.completeExceptionally(IllegalStateException("TTS stopped"))}})
    initialized.complete(engine)
   }}
  }
 }
 fun offlineVoices():List<Voice> {val engine=initialized.get(15,TimeUnit.SECONDS);return engine.voices.orEmpty().filter{!it.isNetworkConnectionRequired&&TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()}.sortedBy{it.name}}
 override fun request(rule:ScheduleRule,ref:String,done:(Result<ReadyAudio>)->Unit){executor.execute{
  val id=UUID.randomUUID().toString();val temp=File(files.directory,id+".part");var engine:TextToSpeech?=null
  val result:Result<ReadyAudio> = try {
   validateRule(rule).requireValid();check(files.directory.usableSpace>30L*1024*1024){"Not enough space for offline audio"}
   engine=initialized.get(15,TimeUnit.SECONDS)
   val voices=offlineVoices();val selected=context.getSharedPreferences("settings",Context.MODE_PRIVATE).getString("voice",null)
   val voice=if(selected!=null)voices.find{it.name==selected}else voices.find{it.locale.language==Locale.getDefault().language}
   requireNotNull(voice){"Choose an installed offline voice for your language"}
   check(engine.setVoice(voice)==TextToSpeech.SUCCESS&&engine.isLanguageAvailable(voice.locale)>=TextToSpeech.LANG_AVAILABLE){"Offline voice unavailable"}
   val completion=CompletableFuture<Unit>();pending[id]=completion
   check(engine.synthesizeToFile(rule.body,Bundle(),temp,id)==TextToSpeech.SUCCESS){"TTS request rejected"}
   completion.get(45,TimeUnit.SECONDS)
   val ready=files.commit(temp,ref)
   val dir=Os.open(files.directory.path,OsConstants.O_RDONLY,0);try{Os.fsync(dir)}finally{Os.close(dir)}
   content.saveAudio(ready)
   Result.success(ready)
  }catch(e:Exception){engine?.stop();Result.failure(e)}finally{pending.remove(id);temp.delete()}
  done(result)
 }}
 override fun inspect(ref:String):ReadyAudio? {val metadata=content.audio(ref)?:return null;return files.inspect(ref)?.takeIf{it==metadata}}
 fun close(){executor.shutdownNow();pending.values.forEach{it.completeExceptionally(IllegalStateException("TTS closed"))};tts?.shutdown()}
}
