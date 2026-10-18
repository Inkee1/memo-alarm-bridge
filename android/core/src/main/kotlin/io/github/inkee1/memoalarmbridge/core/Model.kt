package io.github.inkee1.memoalarmbridge.core
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

data class Note(@SerializedName("note_id") val noteId:String="",val text:String="",@SerializedName("audio_blob_sha256") val audioBlobSha256:String="",@SerializedName("created_at") val createdAt:String="",@SerializedName("updated_at") val updatedAt:String="")
data class ScheduleRule(
 @SerializedName("schedule_id") val scheduleId:String="",
 @SerializedName("epoch_id") val epochId:String="",
 val kind:String="one_shot",
 @SerializedName("at_utc") val atUtc:String="",
 @SerializedName("local_time") val localTime:String="",
 @SerializedName("zone_id") val zoneId:String="",
 @SerializedName("week_days") val weekDays:List<Int> = emptyList(),
 val body:String="",val enabled:Boolean=true,
 @SerializedName("respect_silent") val respectSilent:Boolean=false
)
data class Occurrence(val id:String,@SerializedName("at_utc") val atUtc:String,@SerializedName("audio_sha256") val audioSha256:String="")
data class Version(@SerializedName("server_revision") val serverRevision:Long?=null,@SerializedName("local_generation") val localGeneration:Long=0)
data class Mutation(@SerializedName("schema_version") val schemaVersion:Int=1,@SerializedName("operation_id") val operationId:String,@SerializedName("entity_type") val entityType:String,@SerializedName("entity_id") val entityId:String,@SerializedName("base_revision") val baseRevision:Long,val action:String,val payload:JsonObject)
data class Entity(@SerializedName("entity_type") val entityType:String,@SerializedName("entity_id") val entityId:String,@SerializedName("server_revision") val serverRevision:Long,val payload:JsonObject,val deleted:Boolean=false)
data class DeviceAck(@SerializedName("device_id") val deviceId:String,@SerializedName("schedule_id") val scheduleId:String,@SerializedName("epoch_id") val epochId:String,@SerializedName("server_revision") val serverRevision:Long,@SerializedName("local_generation") val localGeneration:Long,val state:String,@SerializedName("armed_occurrences") val armedOccurrences:List<Occurrence>,@SerializedName("observed_at") val observedAt:String,val capabilities:Map<String,String>)
data class ValidationResult(val valid:Boolean,val error:String?=null) {fun requireValid(){require(valid){error ?: "Invalid input"}}}
private val idRegex=Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
fun validId(value:String):Boolean=idRegex.matches(value)
fun validHash(value:String):Boolean=Regex("^[0-9a-f]{64}$").matches(value)
private fun checked(block:()->Unit):ValidationResult=try{block();ValidationResult(true)}catch(e:Exception){ValidationResult(false,e.message ?: "Invalid fields")}
fun validateNote(note:Note):ValidationResult=checked{
 require(validId(note.noteId)){"Invalid note ID"}
 require(note.text.codePointCount(0,note.text.length)<=32768){"Note exceeds 32768 characters"}
 require(note.audioBlobSha256.isEmpty()||validHash(note.audioBlobSha256)){"Invalid audio hash"}
}
fun validateRule(rule:ScheduleRule):ValidationResult=checked{
 require(validId(rule.scheduleId)&&validId(rule.epochId)){"Invalid schedule or epoch ID"}
 require(rule.body.codePointCount(0,rule.body.length) in 1..2000){"Speech must be 1–2000 characters"}
 when(rule.kind){
  "one_shot"->Instant.parse(rule.atUtc)
  "daily","weekly"->{
   require(rule.localTime.length==5){"Use HH:mm"};LocalTime.parse(rule.localTime);ZoneId.of(rule.zoneId)
   if(rule.kind=="weekly"){
    require(rule.weekDays.isNotEmpty()&&rule.weekDays.size<=7){"Select weekdays"}
    require(rule.weekDays.all{it in 1..7}&&rule.weekDays.distinct().size==rule.weekDays.size){"Invalid weekdays"}
   }
  }
  else->throw IllegalArgumentException("Unsupported recurrence")
 }
}
fun validateMutation(m:Mutation):ValidationResult=checked {
 require(m.schemaVersion==1&&validId(m.operationId)&&validId(m.entityId)&&m.baseRevision>=0){"Invalid mutation envelope"}
 require(m.entityType in listOf("note","schedule")){"Unknown entity type"}
 if(m.action=="delete"&&m.entityType=="note"){require(m.payload.keySet().isEmpty()){ "Delete payload must be an empty object" };return@checked}
 if(m.action=="cancel"&&m.entityType=="schedule"){
  require(m.payload.keySet()==setOf("epoch_id")&&validId(m.payload["epoch_id"].asString)){"Valid epoch required"};return@checked
 }
 require(m.action=="upsert"){"Invalid action"}
 val gson=com.google.gson.Gson()
 if(m.entityType=="note"){
  require(m.payload.keySet().all{it in setOf("note_id","text","audio_blob_sha256","created_at","updated_at")}){"Unknown note field"}
  val note=gson.fromJson(m.payload,Note::class.java);require(note.noteId==m.entityId);validateNote(note).requireValid()
 }else{
  require(m.payload.keySet().all{it in setOf("schedule_id","epoch_id","kind","at_utc","local_time","zone_id","week_days","body","enabled","respect_silent")}){"Unknown schedule field"}
  val rule=gson.fromJson(m.payload,ScheduleRule::class.java);require(rule.scheduleId==m.entityId);validateRule(rule).requireValid()
 }
}
