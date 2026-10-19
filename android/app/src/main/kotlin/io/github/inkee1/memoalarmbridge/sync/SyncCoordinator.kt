package io.github.inkee1.memoalarmbridge.sync

import android.content.Context
import com.google.gson.Gson
import io.github.inkee1.memoalarmbridge.AppGraph
import io.github.inkee1.memoalarmbridge.alarm.PermissionSnapshot
import io.github.inkee1.memoalarmbridge.core.*
import java.io.File
import java.time.Instant

data class ConnectionInfo(val endpoint:String,val fingerprint:String,val deviceId:String)
data class SyncRunReport(val accepted:Int,val conflicts:Int,val pending:Int,val preparing:Int,val more:Boolean)

class SyncCoordinator(private val context:Context,private val graph:AppGraph) {
    private val gson=Gson()
    private val secrets=DeviceSecrets(context)
    private val runLock=Any()
    private val prefs=context.getSharedPreferences("sync-status",Context.MODE_PRIVATE)
    private fun identity(c:ConnectionCredential)=c.endpoint.trimEnd('/')+"#"+c.certSha256
    private fun credential():ConnectionCredential {val c=secrets.load()?:error("Pair this phone with your bridge first");val state=graph.content().read();check(state.linked&&state.connectionIdentity==identity(c)){"Connection setup is incomplete; review and pair again"};return c}
    val engine by lazy {SyncEngine(graph.content(),object:SyncTransport {
        override fun exchange(request:SyncRequest):SyncResponse {
            val client=BridgeClient(credential())
            for(op in request.operations){if(op.entityType=="note"&&op.action=="upsert"){val hash=op.payload["audio_blob_sha256"]?.asString.orEmpty();if(hash.isNotEmpty())client.uploadBlob(hash,File(context.filesDir,"recordings/$hash.m4a"))}}
            val response=client.exchange(request)
            validateSyncResponse(request,response)
            val notePayloads=response.changes.filter{it.mutation.entityType=="note"&&it.mutation.action=="upsert"}.map{it.mutation.payload}+response.results.flatMap{listOfNotNull(it.entity,it.conflictCurrent)}.filter{it.entityType=="note"&&!it.deleted}.map{it.payload}
            for(payload in notePayloads){val hash=payload["audio_blob_sha256"]?.asString.orEmpty();if(hash.isNotEmpty())client.downloadBlob(hash,File(context.filesDir,"recordings"))}
            return response
        }
    })}
    fun info():ConnectionInfo?=secrets.load()?.let{ConnectionInfo(it.endpoint,it.certSha256,it.deviceId)}
    fun lastError():String=prefs.getString("last_error","").orEmpty()
    fun pair(invite:ConnectionInvite,name:String):ConnectionInfo {
        val old=secrets.load();val next=BridgeClient.pair(invite,name)
        secrets.save(next)
        try{engine.attach(old==null||identity(old)!=identity(next),identity(next))}catch(e:Exception){runCatching{if(old==null)secrets.clear()else secrets.save(old)};throw e}
        prefs.edit().remove("last_error").putInt("ack_offset",0).apply();SyncJobs.enable(context)
        return ConnectionInfo(next.endpoint,next.certSha256,next.deviceId)
    }
    fun unpair(){engine.detach();SyncJobs.disable(context);secrets.clear();prefs.edit().remove("last_error").apply()}
    private fun prepareDesired():List<String> {
        val errors=mutableListOf<String>()
        for((rule,version) in graph.content().rules())runCatching{graph.engine.prepare(rule,version)}.onFailure{errors+=it.message?:"Phone preparation failed"}
        return errors
    }
    private fun acknowledgements(deviceId:String):List<DeviceAck> {
        val state=graph.content().read();val ledger=graph.ledger.read();val capabilities=PermissionSnapshot.read(context).values
        val hashes=mutableMapOf<String,String?>();val now=Instant.now()
        return state.records.values.filter{it.entityType=="schedule"}.sortedBy{it.key}.mapNotNull{record->
            val rule=gson.fromJson(record.payload,ScheduleRule::class.java)
            buildDeviceAck(deviceId,rule,record.version,graph.engine.report(rule.scheduleId),"${rule.scheduleId}:${rule.epochId}" in ledger.cancelledEpochs,capabilities,{ref->hashes.getOrPut(ref){runCatching{graph.audio().inspect(ref)?.sha256}.getOrNull()}},now,!record.dirty&&record.conflict==null,record.bridgeGenerationFloor)?.let{if(record.conflict!=null)it.copy(state="conflict",armedOccurrences=emptyList())else it}
        }
    }
    private fun ackBatch(deviceId:String):List<DeviceAck>{val all=acknowledgements(deviceId);if(all.size<=100)return all;val offset=prefs.getInt("ack_offset",0).coerceAtLeast(0)%all.size;return (all.drop(offset)+all.take(offset)).take(100)}
    fun run():SyncRunReport=synchronized(runLock){
        try {
            check(graph.unlocked()){ "Unlock the phone before syncing" };val c=credential()
            val preparationErrors=prepareDesired().toMutableList();var total=0;var outcome=SyncOutcome(0,0,0,false)
            var pages=0
            do {
                outcome=engine.syncOnce(ackBatch(c.deviceId));total+=outcome.accepted;pages++
                prefs.edit().putInt("ack_offset",prefs.getInt("ack_offset",0)+100).apply()
                preparationErrors+=prepareDesired()
            }while(pages<16&&(outcome.more||(outcome.pending>0&&outcome.accepted>0)))
            // Confirm newly prepared/cancelled state after applying the received changes.
            if(!outcome.more){outcome=engine.syncOnce(ackBatch(c.deviceId));total+=outcome.accepted;preparationErrors+=prepareDesired()}
            val client=BridgeClient(credential())
            graph.content().notes().map{it.note.audioBlobSha256}.filter{it.isNotEmpty()}.distinct().filterNot{File(context.filesDir,"recordings/$it.m4a").isFile}.take(16).forEach{client.downloadBlob(it,File(context.filesDir,"recordings"))}
            val preparing=graph.content().rules().count{graph.engine.report(it.first.scheduleId).state=="preparing"}
            prefs.edit().putString("last_error",preparationErrors.distinct().joinToString("; ").take(1000)).apply()
            SyncRunReport(total,outcome.conflicts,outcome.pending,preparing,outcome.more)
        }catch(e:Exception){prefs.edit().putString("last_error",(e.message?:"Sync failed; local data retained").take(1000)).apply();throw e}
    }
}
