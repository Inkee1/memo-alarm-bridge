package io.github.inkee1.memoalarmbridge.core

import com.google.gson.Gson
import java.time.Instant

class StaleDraftException(val current:LocalRecord?):IllegalStateException("The saved item changed while this draft was open. Review both copies before saving.")

/** Local edits share the same lock and transactional write boundary as sync. */
class LocalEditor(private val repository:SyncPort,private val now:()->Instant=Instant::now) {
    private val gson=Gson()
    private fun checkedCurrent(state:SyncState,key:String,expectedVersion:Version?):LocalRecord? {
        val current=state.records[key]
        if(current?.version!=expectedVersion)throw StaleDraftException(current?.copy(payload=current.payload.deepCopy()))
        return current
    }
    fun saveNote(note:Note,expectedVersion:Version?):LocalRecord=synchronized(repository){
        validateNote(note).requireValid()
        val state=repository.read();val key="note:${note.noteId}";val previous=checkedCurrent(state,key,expectedVersion)
        val previousNote=previous?.let{gson.fromJson(it.payload,Note::class.java)}
        val value=note.copy(createdAt=previousNote?.createdAt?.takeIf{it.isNotEmpty()}?:now().toString(),updatedAt=now().toString())
        val record=LocalRecord("note",note.noteId,gson.toJsonTree(value).asJsonObject,Version(previous?.version?.serverRevision,(previous?.version?.localGeneration?:0)+1),conflict=previous?.conflict,bridgeGenerationFloor=previous?.bridgeGenerationFloor?:1)
        repository.write(state.copy(records=state.records+(key to record)));record
    }
    fun saveRule(rule:ScheduleRule,expectedVersion:Version?):LocalRecord=synchronized(repository){
        validateRule(rule).requireValid()
        val state=repository.read();val key="schedule:${rule.scheduleId}";val previous=checkedCurrent(state,key,expectedVersion)
        val conflict=previous?.conflict?.takeUnless{it.reason=="cancelled"&&previous.payload["epoch_id"].asString!=rule.epochId}
        val record=LocalRecord("schedule",rule.scheduleId,gson.toJsonTree(rule).asJsonObject,Version(previous?.version?.serverRevision,(previous?.version?.localGeneration?:0)+1),conflict=conflict,bridgeGenerationFloor=previous?.bridgeGenerationFloor?:1)
        repository.write(state.copy(records=state.records+(key to record),cancelledEpochs=if(rule.enabled)state.cancelledEpochs else state.cancelledEpochs+"${rule.scheduleId}:${rule.epochId}"));record
    }
    fun deleteNote(id:String,expectedVersion:Version?):Unit=synchronized(repository){
        require(validId(id));val state=repository.read();val key="note:$id";val previous=checkedCurrent(state,key,expectedVersion)?:return@synchronized
        val payload=gson.toJsonTree(Note(id,updatedAt=now().toString())).asJsonObject
        repository.write(state.copy(records=state.records+(key to previous.copy(payload=payload,version=previous.version.copy(localGeneration=previous.version.localGeneration+1),deleted=true,dirty=true,syncError=""))))
    }
}
