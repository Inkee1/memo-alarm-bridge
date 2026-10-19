package io.github.inkee1.memoalarmbridge.core

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import java.time.Instant
import java.util.UUID

data class MutationResult(@SerializedName("operation_id")val operationId:String,@SerializedName("server_revision")val serverRevision:Long,val status:String,@SerializedName("conflict_current")val conflictCurrent:Entity?=null,val entity:Entity?=null,val error:String="")
data class RemoteChange(@SerializedName("server_revision")val serverRevision:Long,val mutation:Mutation)
data class SyncRequest(@SerializedName("schema_version")val schemaVersion:Int=1,val cursor:Long=0,val operations:List<Mutation> = emptyList(),val acks:List<DeviceAck> = emptyList())
data class SyncResponse(@SerializedName("schema_version")val schemaVersion:Int=1,val cursor:Long=0,val more:Boolean=false,val results:List<MutationResult> = emptyList(),val changes:List<RemoteChange> = emptyList())
data class SyncConflict(val remote:Entity?=null,val revision:Long=0,val reason:String="edit",val cancelledDraft:JsonObject?=null)
data class LocalRecord(val entityType:String="",val entityId:String="",val payload:JsonObject=JsonObject(),val version:Version=Version(),val deleted:Boolean=false,val dirty:Boolean=true,val conflict:SyncConflict?=null,val syncError:String="",val bridgeGenerationFloor:Long=1) {val key get()="$entityType:$entityId"}
data class FrozenOperation(val operation:Mutation,val generation:Long)
data class SyncState(val records:Map<String,LocalRecord> = emptyMap(),val cursor:Long=0,val inFlight:Map<String,FrozenOperation> = emptyMap(),val cancelledEpochs:Set<String> = emptySet(),val lastContact:String="",val linked:Boolean=false,val connectionEpoch:String="",val connectionIdentity:String="")
data class SyncOutcome(val accepted:Int,val conflicts:Int,val pending:Int,val more:Boolean)
interface SyncPort {fun read():SyncState;fun write(value:SyncState)}
interface SyncTransport {fun exchange(request:SyncRequest):SyncResponse}

/** Network retries use immutable persisted operations, while edits remain independent. */
class SyncEngine(private val repository:SyncPort,private val transport:SyncTransport,private val now:()->Instant=Instant::now) {
    private val gson=Gson()
    private val exchangeLock=Any()
    fun syncOnce(acks:List<DeviceAck> = emptyList()):SyncOutcome=synchronized(exchangeLock){
        val frozen=synchronized(repository){
            val state=repository.read();check(state.linked){"No bridge connection"}
            val flights=state.inFlight.toMutableMap()
            for(record in state.records.values){
                if(flights.size>=16)break
                if(!record.dirty||record.conflict!=null||record.key in flights)continue
                val mutation=freeze(record);flights[record.key]=FrozenOperation(mutation,record.version.localGeneration)
            }
            val next=state.copy(inFlight=flights);repository.write(next)
            SyncRequest(cursor=next.cursor,operations=flights.values.map{it.operation},acks=acks.take(100)) to next.connectionEpoch
        }
        val request=frozen.first
        val response=transport.exchange(request)
        validateSyncResponse(request,response)
        synchronized(repository){
            val initial=repository.read();check(initial.linked&&initial.connectionEpoch==frozen.second){"Connection changed during sync; old response ignored"}
            var state=initial;var accepted=0
            for(result in response.results){
                val item=state.inFlight.entries.find{it.value.operation.operationId==result.operationId}?:continue
                val key=item.key;val flight=item.value;val current=state.records[key]?:continue
                when(result.status){
                    "stored","deleted","pending_delivery","cancel_pending"->{
                        val authoritative=result.entity
                        val remoteRevision=authoritative?.serverRevision
                        val newer=current.version.localGeneration>flight.generation
                        if(newer&&flight.operation.action=="cancel"&&authoritative!=null&&authoritative.payload["epoch_id"].asString!=flight.operation.payload["epoch_id"].asString&&authoritative.payload!=current.payload){
                            state=state.copy(records=state.records+(key to current.copy(conflict=SyncConflict(authoritative,authoritative.serverRevision),syncError="")),inFlight=state.inFlight-key,cancelledEpochs=state.cancelledEpochs+"${current.entityId}:${flight.operation.payload["epoch_id"].asString}")
                            accepted++;continue
                        }
                        val version=current.version.copy(serverRevision=remoteRevision,localGeneration=current.version.localGeneration+if(current.version.serverRevision!=remoteRevision)1 else 0)
                        val updated=current.copy(payload=if(!newer&&authoritative!=null)authoritative.payload.deepCopy()else current.payload,deleted=if(!newer&&authoritative!=null)authoritative.deleted else current.deleted,version=version,dirty=newer,syncError="")
                        state=state.copy(records=state.records+(key to updated),inFlight=state.inFlight-key);accepted++
                    }
                    "conflict"->{
                        state=state.copy(records=state.records+(key to current.copy(conflict=SyncConflict(result.conflictCurrent,result.serverRevision),syncError="")),inFlight=state.inFlight-key)
                    }
                    "cancelled"->{
                        val epoch=flight.operation.payload["epoch_id"]?.asString
                        if(current.entityType=="schedule"&&epoch!=null)state=cancelEpoch(state,current.entityId,epoch,current.version.serverRevision?:0)
                        state=state.copy(inFlight=state.inFlight-key)
                    }
                    "rejected"->state=state.copy(records=state.records+(key to current.copy(syncError=result.error.ifEmpty{"Operation rejected"})),inFlight=state.inFlight-key)
                }
            }
            for(change in response.changes)state=applyRemote(state,change)
            state=state.copy(cursor=response.cursor,lastContact=now().toString())
            repository.write(state)
            SyncOutcome(accepted,state.records.values.count{it.conflict!=null},state.records.values.count{it.dirty},response.more)
        }
    }
    private fun freeze(record:LocalRecord):Mutation {
        require(record.version.localGeneration>0)
        val action=when{record.entityType=="note"&&record.deleted->"delete";record.entityType=="schedule"&&!record.payload["enabled"].asBoolean->"cancel";else->"upsert"}
        val payload=when(action){"delete"->JsonObject();"cancel"->JsonObject().apply{addProperty("epoch_id",record.payload["epoch_id"].asString)};else->record.payload.deepCopy()}
        return Mutation(operationId=UUID.randomUUID().toString(),entityType=record.entityType,entityId=record.entityId,baseRevision=record.version.serverRevision?:0,action=action,payload=payload).also{validateMutation(it).requireValid()}
    }
    private fun applyRemote(state:SyncState,change:RemoteChange):SyncState {
        val mutation=change.mutation;val key="${mutation.entityType}:${mutation.entityId}";val current=state.records[key]
        if(mutation.action=="cancel")return cancelEpoch(state,mutation.entityId,mutation.payload["epoch_id"].asString,change.serverRevision)
        val payload=if(mutation.action=="delete")gson.toJsonTree(Note(mutation.entityId)).asJsonObject else mutation.payload.deepCopy()
        if(mutation.entityType=="schedule"&&!payload["enabled"].asBoolean)return cancelEpoch(state,mutation.entityId,payload["epoch_id"].asString,change.serverRevision,payload)
        if(mutation.entityType=="schedule"&&"${mutation.entityId}:${payload["epoch_id"].asString}" in state.cancelledEpochs)return state
        val remote=Entity(mutation.entityType,mutation.entityId,change.serverRevision,payload,mutation.action=="delete")
        if(current!=null){
            if(change.serverRevision<(current.version.serverRevision?:0))return state
            if(change.serverRevision==current.version.serverRevision){
                // A previously acknowledged own change may supply normalized timestamps.
                return if(current.dirty||current.conflict!=null)state else state.copy(records=state.records+(key to current.copy(payload=payload,deleted=remote.deleted)))
            }
            if(current.dirty||current.conflict!=null){
                val old=current.conflict
                if(old!=null&&old.revision>change.serverRevision)return state
                return state.copy(records=state.records+(key to current.copy(conflict=SyncConflict(remote,change.serverRevision),syncError="")))
            }
        }
        val record=LocalRecord(mutation.entityType,mutation.entityId,payload,Version(change.serverRevision,(current?.version?.localGeneration?:0)+1),remote.deleted,false,bridgeGenerationFloor=current?.bridgeGenerationFloor?:1)
        return state.copy(records=state.records+(key to record))
    }
    private fun cancelEpoch(state:SyncState,id:String,epoch:String,revision:Long,fullPayload:JsonObject?=null):SyncState {
        val key="schedule:$id";val tombstones=state.cancelledEpochs+"$id:$epoch"
        val current=state.records[key]?:return if(fullPayload==null)state.copy(cancelledEpochs=tombstones)else state.copy(cancelledEpochs=tombstones,records=state.records+(key to LocalRecord("schedule",id,fullPayload.deepCopy(),Version(revision,1),dirty=false)))
        if(current.payload["epoch_id"].asString!=epoch)return state.copy(cancelledEpochs=tombstones)
        val payload=current.payload.deepCopy().apply{addProperty("enabled",false)}
        val remote=if(revision>0)Entity("schedule",id,revision,fullPayload?.deepCopy()?:payload)else null
        val conflict=if(current.dirty&&current.payload["enabled"].asBoolean)SyncConflict(remote,revision,"cancelled",current.payload.deepCopy())else current.conflict?.let{if(it.reason=="cancelled"&&revision>it.revision)it.copy(remote=remote,revision=revision)else it}
        val version=Version(maxOf(current.version.serverRevision?:0,revision).takeIf{it>0},current.version.localGeneration+if(current.payload["enabled"].asBoolean||revision>(current.version.serverRevision?:0))1 else 0)
        val updated=current.copy(payload=if(!current.dirty&&fullPayload!=null)fullPayload.deepCopy()else payload,version=version,dirty=false,conflict=conflict,syncError="")
        return state.copy(records=state.records+(key to updated),inFlight=state.inFlight-key,cancelledEpochs=tombstones)
    }
    fun resolve(key:String,keepLocal:Boolean,expectedRevision:Long,expectedGeneration:Long)=synchronized(repository){
        var state=repository.read();val current=state.records[key]?:throw IllegalArgumentException("Record missing");val conflict=current.conflict?:throw IllegalArgumentException("No conflict")
        require(conflict.revision==expectedRevision&&current.version.localGeneration==expectedGeneration){"The conflict changed; review it again"}
        val remote=conflict.remote
        val payload=if(keepLocal)(conflict.cancelledDraft?:current.payload).deepCopy()else remote?.payload?.deepCopy()?:current.payload.deepCopy()
        if(current.entityType=="schedule"){
            if(keepLocal&&"${current.entityId}:${payload["epoch_id"].asString}" in state.cancelledEpochs&&payload["enabled"].asBoolean)payload.addProperty("epoch_id",UUID.randomUUID().toString())
            if(!keepLocal&&remote==null)payload.addProperty("enabled",false)
            if(!payload["enabled"].asBoolean)state=state.copy(cancelledEpochs=state.cancelledEpochs+"${current.entityId}:${payload["epoch_id"].asString}")
        }
        val record=current.copy(payload=payload,version=Version(remote?.serverRevision,current.version.localGeneration+1),dirty=keepLocal,deleted=if(keepLocal)current.deleted else remote?.deleted?:true,conflict=null,syncError="")
        repository.write(state.copy(records=state.records+(key to record),inFlight=state.inFlight-key))
    }
    fun detach()=synchronized(repository){repository.write(repository.read().copy(linked=false,connectionEpoch=UUID.randomUUID().toString()))}
    fun attach(newBridge:Boolean,identity:String="")=synchronized(repository){
        val state=repository.read()
        if(!newBridge)repository.write(state.copy(linked=true,connectionEpoch=UUID.randomUUID().toString(),connectionIdentity=identity))else repository.write(state.copy(linked=true,connectionEpoch=UUID.randomUUID().toString(),connectionIdentity=identity,cursor=0,inFlight=emptyMap(),lastContact="",records=state.records.mapValues{(_,r)->r.copy(version=Version(null,r.version.localGeneration+1),bridgeGenerationFloor=r.version.localGeneration+1,dirty=true,conflict=null,syncError="")}))
    }
}

/** Validate before downloading referenced blobs and again at the durable application boundary. */
fun validateSyncResponse(request:SyncRequest,response:SyncResponse){
    require(response.schemaVersion==1&&response.changes.size<=16&&response.cursor>=request.cursor){"Invalid sync response"}
    require(response.results.size==request.operations.size&&response.results.map{it.operationId}.toSet()==request.operations.map{it.operationId}.toSet()){"Incomplete operation results"}
    var cursor=request.cursor
    for(change in response.changes){require(change.serverRevision==cursor+1){"Non-contiguous change history"};validateMutation(change.mutation).requireValid();cursor=change.serverRevision}
    require(response.cursor==cursor){"Cursor does not match durable changes"}
    for(result in response.results){
        require(result.status in listOf("stored","deleted","pending_delivery","cancel_pending","conflict","cancelled","rejected")&&result.serverRevision>=0){"Unknown operation result"}
        val sent=request.operations.single{it.operationId==result.operationId}
        listOfNotNull(result.entity,result.conflictCurrent).forEach{entity->
            require(entity.entityType==sent.entityType&&entity.entityId==sent.entityId&&entity.serverRevision>0&&entity.serverRevision<=result.serverRevision)
            validateMutation(Mutation(operationId=UUID.randomUUID().toString(),entityType=entity.entityType,entityId=entity.entityId,baseRevision=0,action="upsert",payload=entity.payload)).requireValid()
        }
        if(result.status in listOf("stored","deleted","pending_delivery"))require(result.entity!=null){"Missing authoritative entity"}
    }
}
