package io.github.inkee1.memoalarmbridge

import android.content.Context
import android.os.UserManager
import io.github.inkee1.memoalarmbridge.alarm.AndroidScheduler
import io.github.inkee1.memoalarmbridge.audio.TtsPreparer
import io.github.inkee1.memoalarmbridge.core.*
import io.github.inkee1.memoalarmbridge.data.*
import io.github.inkee1.memoalarmbridge.sync.*
import java.time.Instant
import java.util.concurrent.Executors

class AppGraph private constructor(private val context:Context) {
    val work=Executors.newSingleThreadExecutor()
    // Exact broadcasts must not queue behind UI/network/synthesis work.
    val triggers=Executors.newSingleThreadExecutor()
    val syncWork=Executors.newSingleThreadExecutor()
    val ledger=LedgerDatabase(context)
    val scheduler=AndroidScheduler(context)
    private val privateContent by lazy {ContentDatabase(context)}
    private val privateAudio by lazy {TtsPreparer(context,content())}
    private val coordinator by lazy {SyncCoordinator(context,this)}
    fun unlocked()=context.getSystemService(UserManager::class.java).isUserUnlocked
    fun content():ContentDatabase {check(unlocked());return privateContent}
    fun audio():TtsPreparer {check(unlocked());return privateAudio}
    fun sync():SyncCoordinator {check(unlocked());return coordinator}
    fun requestSync(){if(unlocked())work.execute{runCatching{if(content().read().linked)SyncJobs.request(context)}}}
    private val lazyAudio=object:AudioPort {
        override fun request(rule:ScheduleRule,ref:String,done:(Result<ReadyAudio>)->Unit)=audio().request(rule,ref){result->done(result);requestSync()}
        override fun inspect(ref:String)=audio().inspect(ref)
    }
    val engine=AlarmEngine(ledger,scheduler,lazyAudio,Instant::now,::unlocked,{content().rules()})
    fun saveRule(rule:ScheduleRule,expectedVersion:Version?=null):Version {val version=content().editRule(rule,expectedVersion);engine.prepare(rule,version);requestSync();return version}
    fun cancelRule(id:String,expectedVersion:Version){val stored=content().rule(id)?:return;val disabled=stored.first.copy(enabled=false);content().editRule(disabled,expectedVersion);engine.cancel(disabled.scheduleId,disabled.epochId);requestSync()}
    companion object {
        @Volatile private var instance:AppGraph?=null
        fun get(context:Context):AppGraph=instance?:synchronized(this){instance?:AppGraph(context.applicationContext).also{instance=it}}
    }
}
