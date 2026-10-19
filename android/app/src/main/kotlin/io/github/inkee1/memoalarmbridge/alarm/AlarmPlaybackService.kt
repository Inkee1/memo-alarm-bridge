package io.github.inkee1.memoalarmbridge.alarm

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.net.Uri
import android.os.*
import io.github.inkee1.memoalarmbridge.AppGraph
import io.github.inkee1.memoalarmbridge.R
import io.github.inkee1.memoalarmbridge.core.*
import io.github.inkee1.memoalarmbridge.ui.MainActivity
import java.io.Closeable
import java.io.File
import java.io.FileInputStream

/** Only a durable, process-owned claim may enter this ordered playback queue. */
class AlarmPlaybackService:Service() {
    private lateinit var graph:AppGraph
    private lateinit var audio:AudioManager
    private lateinit var notifications:NotificationManager
    private val handler=Handler(Looper.getMainLooper())
    private val queue=PlaybackQueue()
    private val notificationIds=mutableMapOf<String,Int>()
    private var nextId=2000
    private var player:MediaPlayer?=null
    private var source:Closeable?=null
    private var focus:AudioFocusRequest?=null
    private var playing:String?=null
    private var cap:Runnable?=null
    private val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val poll=object:Runnable {
        override fun run(){
            queue.tokens.filterNot{graph.engine.canPlay(it)}.forEach{remove(it,null)}
            if(queue.tokens.isNotEmpty())handler.postDelayed(this,200)
        }
    }
    override fun onCreate(){
        super.onCreate();graph=AppGraph.get(this);audio=getSystemService(AudioManager::class.java);notifications=getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL,getString(R.string.alarm_channel),NotificationManager.IMPORTANCE_HIGH).apply{setSound(null,null);enableVibration(false);lockscreenVisibility=Notification.VISIBILITY_PUBLIC})
        startForeground(SUMMARY,summary(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }
    override fun onBind(intent:Intent?)=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        val token=intent?.getStringExtra("token")
        if(token!=null&&graph.engine.canPlay(token)) {
            queue.offer(token);notificationIds.getOrPut(token){nextId++};notifications.notify(notificationIds.getValue(token),notice(token))
            handler.removeCallbacks(poll);handler.post(poll);playNext()
        } else if(queue.tokens.isEmpty())stopSelf()
        return START_NOT_STICKY
    }
    private fun summary():Notification=Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_alarm).setContentTitle(getString(R.string.app_name)).setContentText(getString(R.string.alarm_active)).setOngoing(true).setGroup("alarms").setGroupSummary(true).setOnlyAlertOnce(true).setContentIntent(openApp()).build()
    private fun openApp()=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun action(token:String,action:String)=PendingIntent.getBroadcast(this,0,Intent(this,AlarmActions::class.java).setAction("io.github.inkee1.memoalarmbridge."+action).setData(Uri.Builder().scheme("memoalarm").authority(action.lowercase()).appendPath(token).build()).putExtra("token",token),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun notice(token:String):Notification=Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_alarm).setContentTitle(getString(R.string.alarm_active)).setContentText(getString(R.string.alarm_controls)).setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PUBLIC).setCategory(Notification.CATEGORY_ALARM).setGroup("alarms").setContentIntent(openApp()).addAction(Notification.Action.Builder(null,getString(R.string.stop),action(token,"STOP")).build()).addAction(Notification.Action.Builder(null,getString(R.string.snooze),action(token,"SNOOZE")).build()).build()
    private fun playNext() {
        if(playing!=null)return
        val token=queue.active?:return stopWhenEmpty()
        if(!graph.engine.canPlay(token)){remove(token,null);return}
        val entry=graph.ledger.read().entries[token]?:return remove(token,"interrupted")
        val dnd=when(notifications.currentInterruptionFilter){NotificationManager.INTERRUPTION_FILTER_ALL->DndMode.ALL;NotificationManager.INTERRUPTION_FILTER_ALARMS->DndMode.ALARMS;NotificationManager.INTERRUPTION_FILTER_PRIORITY->DndMode.PRIORITY;NotificationManager.INTERRUPTION_FILTER_NONE->DndMode.NONE;else->DndMode.UNKNOWN}
        if(!PlaybackPolicy.allowed(audio.getStreamVolume(AudioManager.STREAM_ALARM),audio.ringerMode!=AudioManager.RINGER_MODE_NORMAL,entry.respectSilent,dnd)){remove(token,"interrupted");return}
        playing=token
        val request=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false).setWillPauseWhenDucked(true).setOnAudioFocusChangeListener({change->if(change<0&&playing==token)interruptAll()},handler).build()
        focus=request
        if(audio.requestAudioFocus(request)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){interruptAll();return}
        try {
            val media=MediaPlayer();player=media;media.setAudioAttributes(attributes);media.setWakeMode(this,PowerManager.PARTIAL_WAKE_LOCK)
            if(graph.unlocked()) {
                check(graph.audio().inspect(entry.audioRef)!=null){"Prepared audio missing"}
                val input=FileInputStream(File(graph.audio().files.directory,entry.audioRef+".wav"));source=input;media.setDataSource(input.fd)
            } else {
                val asset=resources.openRawResourceFd(R.raw.generic_alarm);source=asset;media.setDataSource(asset.fileDescriptor,asset.startOffset,asset.length)
            }
            media.setOnPreparedListener {
                if(playing!=token)return@setOnPreparedListener
                try {
                    if(!graph.engine.withPlaybackAuthority(token){media.start()}){remove(token,null);return@setOnPreparedListener}
                    cap=Runnable{remove(token,"interrupted")}.also{handler.postDelayed(it,120000)}
                }catch(_:Exception){remove(token,"interrupted")}
            }
            media.setOnCompletionListener{remove(token,"fired")}
            media.setOnErrorListener{_,_,_->remove(token,"interrupted");true}
            media.prepareAsync()
        }catch(_:Exception){remove(token,"interrupted")}
    }
    private fun releasePlayer(){
        playing=null;cap?.let(handler::removeCallbacks);cap=null
        player?.let{runCatching{it.setOnCompletionListener(null);it.setOnErrorListener(null);it.setOnPreparedListener(null);it.release()}};player=null
        runCatching{source?.close()};source=null
        focus?.let{audio.abandonAudioFocusRequest(it)};focus=null
    }
    private fun remove(token:String,state:String?){
        if(state!=null){runCatching{graph.engine.finish(token,state)};graph.requestSync()}
        if(playing==token)releasePlayer()
        queue.remove(token);notificationIds.remove(token)?.let(notifications::cancel)
        if(queue.tokens.isEmpty())stopWhenEmpty()else playNext()
    }
    private fun interruptAll(){val all=queue.tokens;queue.clear();releasePlayer();all.forEach{runCatching{graph.engine.finish(it,"interrupted")};notificationIds.remove(it)?.let(notifications::cancel)};graph.requestSync();stopWhenEmpty()}
    private fun stopWhenEmpty(){if(queue.tokens.isEmpty()){handler.removeCallbacks(poll);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}}
    override fun onDestroy(){handler.removeCallbacksAndMessages(null);val all=queue.tokens;queue.clear();releasePlayer();all.forEach{runCatching{graph.engine.finish(it,"interrupted")};notificationIds.remove(it)?.let(notifications::cancel)};if(all.isNotEmpty())graph.requestSync();super.onDestroy()}
    companion object {private const val CHANNEL="alarm-playback";private const val SUMMARY=1000}
}
